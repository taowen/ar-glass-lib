#include <jni.h>
#include <android/log.h>

#include <array>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

namespace {
constexpr std::uint16_t kCommandDpGetCurrentEdid = 0x275e;
constexpr std::uint16_t kCommandDpSetCurrentEdid = 0x275f;
constexpr std::uint16_t kCommandDpSetInputMode = 0x2822;
constexpr std::size_t kFramePrefixSize = 6;
constexpr std::size_t kHeaderSize = 10;
constexpr std::size_t kMaxFramePayload = 4096;

class ScopedFd {
public:
    explicit ScopedFd(int fd = -1) : fd_(fd) {}
    ~ScopedFd() { reset(); }
    ScopedFd(const ScopedFd&) = delete;
    ScopedFd& operator=(const ScopedFd&) = delete;
    ScopedFd(ScopedFd&& other) noexcept : fd_(other.release()) {}
    ScopedFd& operator=(ScopedFd&& other) noexcept {
        if (this != &other) reset(other.release());
        return *this;
    }
    int get() const { return fd_; }
    int release() {
        const int fd = fd_;
        fd_ = -1;
        return fd;
    }
    void reset(int fd = -1) {
        if (fd_ >= 0) ::close(fd_);
        fd_ = fd;
    }

private:
    int fd_;
};

[[noreturn]] void throw_error(JNIEnv* env, const char* message) {
    __android_log_print(ANDROID_LOG_ERROR, "ArGlassNative", "%s", message);
    const auto exception = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(exception, message);
    throw std::runtime_error(message);
}

std::uint32_t read_be32(const std::uint8_t* data) {
    return (static_cast<std::uint32_t>(data[0]) << 24) |
           (static_cast<std::uint32_t>(data[1]) << 16) |
           (static_cast<std::uint32_t>(data[2]) << 8) |
           static_cast<std::uint32_t>(data[3]);
}

void write_be16(std::vector<std::uint8_t>& out, std::uint16_t value) {
    out.push_back(static_cast<std::uint8_t>((value >> 8) & 0xff));
    out.push_back(static_cast<std::uint8_t>(value & 0xff));
}

void write_be32(std::vector<std::uint8_t>& out, std::uint32_t value) {
    out.push_back(static_cast<std::uint8_t>((value >> 24) & 0xff));
    out.push_back(static_cast<std::uint8_t>((value >> 16) & 0xff));
    out.push_back(static_cast<std::uint8_t>((value >> 8) & 0xff));
    out.push_back(static_cast<std::uint8_t>(value & 0xff));
}

ScopedFd connect_tcp(const char* host, int port, int connect_timeout_ms, int read_timeout_ms) {
    ScopedFd fd(::socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0));
    if (fd.get() < 0) throw std::runtime_error(std::string("socket failed: ") + std::strerror(errno));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<std::uint16_t>(port));
    if (::inet_pton(AF_INET, host, &addr.sin_addr) != 1) throw std::runtime_error("invalid XREAL One DP address");

    const int flags = ::fcntl(fd.get(), F_GETFL, 0);
    if (flags < 0) throw std::runtime_error(std::string("fcntl get failed: ") + std::strerror(errno));
    if (::fcntl(fd.get(), F_SETFL, flags | O_NONBLOCK) != 0)
        throw std::runtime_error(std::string("fcntl nonblock failed: ") + std::strerror(errno));

    int rc = ::connect(fd.get(), reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    if (rc != 0 && errno != EINPROGRESS) throw std::runtime_error(std::string("connect failed: ") + std::strerror(errno));
    if (rc != 0) {
        fd_set write_set;
        FD_ZERO(&write_set);
        FD_SET(fd.get(), &write_set);
        timeval timeout{connect_timeout_ms / 1000, (connect_timeout_ms % 1000) * 1000};
        rc = ::select(fd.get() + 1, nullptr, &write_set, nullptr, &timeout);
        if (rc == 0) throw std::runtime_error("connect timed out");
        if (rc < 0) throw std::runtime_error(std::string("connect select failed: ") + std::strerror(errno));
        int error = 0;
        socklen_t error_len = sizeof(error);
        if (::getsockopt(fd.get(), SOL_SOCKET, SO_ERROR, &error, &error_len) != 0 || error != 0)
            throw std::runtime_error(std::string("connect completion failed: ") + std::strerror(error));
    }

    if (::fcntl(fd.get(), F_SETFL, flags) != 0)
        throw std::runtime_error(std::string("fcntl restore failed: ") + std::strerror(errno));

    timeval read_timeout{read_timeout_ms / 1000, (read_timeout_ms % 1000) * 1000};
    if (::setsockopt(fd.get(), SOL_SOCKET, SO_RCVTIMEO, &read_timeout, sizeof(read_timeout)) != 0)
        throw std::runtime_error(std::string("setsockopt read timeout failed: ") + std::strerror(errno));

    return fd;
}

std::vector<std::uint8_t> make_request(std::uint16_t command, std::uint8_t sequence, const std::vector<std::uint8_t>& payload) {
    std::vector<std::uint8_t> out;
    out.reserve(kHeaderSize + payload.size());
    write_be16(out, command);
    write_be32(out, static_cast<std::uint32_t>(4 + payload.size()));
    out.push_back(0x80);
    out.push_back(0x00);
    out.push_back(0x00);
    out.push_back(sequence);
    out.insert(out.end(), payload.begin(), payload.end());
    return out;
}

void send_all(int fd, const std::vector<std::uint8_t>& bytes) {
    const std::uint8_t* cursor = bytes.data();
    std::size_t remaining = bytes.size();
    while (remaining > 0) {
        const ssize_t written = ::send(fd, cursor, remaining, 0);
        if (written > 0) {
            cursor += written;
            remaining -= static_cast<std::size_t>(written);
            continue;
        }
        if (written < 0 && errno == EINTR) continue;
        throw std::runtime_error(std::string("send failed: ") + std::strerror(errno));
    }
}

bool pop_frame(std::vector<std::uint8_t>& pending, std::vector<std::uint8_t>& frame) {
    while (pending.size() >= kFramePrefixSize) {
        const std::uint32_t payload_size = read_be32(pending.data() + 2);
        if (payload_size < 4 || payload_size > kMaxFramePayload) {
            pending.erase(pending.begin());
            continue;
        }

        const std::size_t frame_size = kFramePrefixSize + payload_size;
        if (pending.size() < frame_size) return false;

        frame.assign(pending.begin(), pending.begin() + static_cast<std::ptrdiff_t>(frame_size));
        pending.erase(pending.begin(), pending.begin() + static_cast<std::ptrdiff_t>(frame_size));
        return true;
    }
    return false;
}

bool read_matching_response(int fd, std::uint16_t command, std::uint8_t sequence,
                            std::vector<std::uint8_t>& out, bool required) {
    std::vector<std::uint8_t> pending;
    for (int reads = 0; reads < 24; ++reads) {
        std::vector<std::uint8_t> frame;
        while (pop_frame(pending, frame)) {
            if (frame.size() < kHeaderSize) continue;
            const std::uint16_t response_command = (static_cast<std::uint16_t>(frame[0]) << 8) | frame[1];
            const bool is_response = frame[6] == 0x00;
            const std::uint8_t response_sequence = frame[9];
            if (response_command == command && is_response && response_sequence == sequence) {
                out = std::move(frame);
                return true;
            }
        }

        std::array<std::uint8_t, 1024> buffer{};
        const ssize_t count = ::recv(fd, buffer.data(), buffer.size(), 0);
        if (count > 0) {
            pending.insert(pending.end(), buffer.begin(), buffer.begin() + count);
            continue;
        }
        if (count == 0) {
            if (!required) return false;
            throw std::runtime_error("XREAL One DP connection closed");
        }
        if (errno == EINTR) {
            --reads;
            continue;
        }
        if (errno == EAGAIN || errno == EWOULDBLOCK) break;
        throw std::runtime_error(std::string("recv failed: ") + std::strerror(errno));
    }

    if (!required) return false;
    throw std::runtime_error("timed out waiting for XREAL One DP response");
}

bool is_success_ack(const std::vector<std::uint8_t>& frame) {
    return frame.size() >= 12 && frame[10] == 0x22 && frame[11] == 0x00;
}

std::vector<std::uint8_t> transact(int fd, std::uint16_t command, std::uint8_t sequence, const std::vector<std::uint8_t>& request_payload) {
    send_all(fd, make_request(command, sequence, request_payload));
    std::vector<std::uint8_t> frame;
    if (read_matching_response(fd, command, sequence, frame, true)) return frame;
    throw std::runtime_error("timed out waiting for XREAL One DP response");
}

bool send_command_accepting_reenumeration(int fd, std::uint16_t command, std::uint8_t sequence, const std::vector<std::uint8_t>& request_payload) {
    send_all(fd, make_request(command, sequence, request_payload));
    std::vector<std::uint8_t> frame;
    if (!read_matching_response(fd, command, sequence, frame, false)) return true;
    return is_success_ack(frame);
}

int parse_edid_response(const std::vector<std::uint8_t>& frame) {
    if (frame.size() < 14 || frame[10] != 0x22 || frame[11] != 0x02 || frame[12] != 0x10)
        throw std::runtime_error("malformed XREAL One DP EDID response");
    return frame[13] & 0xff;
}

template <typename Fn>
auto with_host(JNIEnv* env, jstring host, Fn&& fn) -> decltype(fn(static_cast<const char*>(nullptr))) {
    const char* host_chars = env->GetStringUTFChars(host, nullptr);
    if (!host_chars) throw_error(env, "failed to read XREAL One DP host");
    try {
        auto result = fn(host_chars);
        env->ReleaseStringUTFChars(host, host_chars);
        return result;
    } catch (...) {
        env->ReleaseStringUTFChars(host, host_chars);
        throw;
    }
}

void throw_java(JNIEnv* env, const std::exception& error) {
    __android_log_print(ANDROID_LOG_ERROR, "ArGlassNative", "%s", error.what());
    const auto exception = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(exception, error.what());
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_taowen_arglass_NativeBridge_xrealOneDpGetCurrentEdid(
        JNIEnv* env, jobject, jstring host, jint port, jint connect_timeout_ms, jint read_timeout_ms) {
    try {
        return with_host(env, host, [&](const char* host_chars) -> jint {
            auto fd = connect_tcp(host_chars, port, connect_timeout_ms, read_timeout_ms);
            const auto frame = transact(fd.get(), kCommandDpGetCurrentEdid, 1, {0x1a, 0x00});
            return static_cast<jint>(parse_edid_response(frame));
        });
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) throw_java(env, error);
        return -1;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_taowen_arglass_NativeBridge_xrealOneDpSetDisplayMode(
        JNIEnv* env, jobject, jstring host, jint port, jint edid, jint input_mode,
        jint connect_timeout_ms, jint read_timeout_ms) {
    try {
        return with_host(env, host, [&](const char* host_chars) -> jboolean {
            auto fd = connect_tcp(host_chars, port, connect_timeout_ms, read_timeout_ms);
            std::uint8_t sequence = 1;
            const bool edid_sent = send_command_accepting_reenumeration(
                fd.get(),
                kCommandDpSetCurrentEdid,
                sequence++,
                {0x1a, 0x02, 0x08, static_cast<std::uint8_t>(edid & 0xff)});
            if (!edid_sent) return JNI_FALSE;

            const bool input_sent = send_command_accepting_reenumeration(
                fd.get(),
                kCommandDpSetInputMode,
                sequence++,
                {0x1a, 0x02, 0x08, static_cast<std::uint8_t>(input_mode & 0xff)});
            return input_sent ? JNI_TRUE : JNI_FALSE;
        });
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) throw_java(env, error);
        return JNI_FALSE;
    }
}
