#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
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
constexpr std::array<std::uint8_t, 6> kHeader = {0x28, 0x36, 0x00, 0x00, 0x00, 0x80};
constexpr std::array<std::uint8_t, 6> kMarker = {0x00, 0x40, 0x1f, 0x00, 0x00, 0x40};
constexpr std::size_t kFrameSize = 84;
constexpr std::size_t kSampleSize = 36;

template <typename T>
T read_le(const std::uint8_t* data) {
    T value{};
    std::memcpy(&value, data, sizeof(T));
    return value;
}

void write_bytes(std::uint8_t* out, std::size_t offset, const void* value, std::size_t size) {
    std::memcpy(out + offset, value, size);
}

struct XrealOneSample {
    std::uint64_t timestamp_nanos = 0;
    std::array<float, 3> acceleration{};
    std::array<float, 3> gyro{};
};

class ScopedFd {
public:
    explicit ScopedFd(int fd = -1) : fd_(fd) {}
    ~ScopedFd() { reset(); }
    ScopedFd(const ScopedFd&) = delete;
    ScopedFd& operator=(const ScopedFd&) = delete;
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

class XrealOneTcpImuSession {
public:
    XrealOneTcpImuSession(const char* host, int port, int connect_timeout_ms, int read_timeout_ms) {
        ScopedFd fd(::socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0));
        if (fd.get() < 0) throw std::runtime_error(std::string("socket failed: ") + std::strerror(errno));

        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(static_cast<std::uint16_t>(port));
        if (::inet_pton(AF_INET, host, &addr.sin_addr) != 1) throw std::runtime_error("invalid XREAL One IMU address");

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

        fd_ = fd.release();
    }

    ~XrealOneTcpImuSession() {
        close();
    }

    bool next(XrealOneSample& out) {
        while (true) {
            if (try_parse(out)) return true;

            std::array<std::uint8_t, 4096> buffer{};
            const ssize_t count = ::recv(fd_, buffer.data(), buffer.size(), 0);
            if (count > 0) {
                pending_.insert(pending_.end(), buffer.begin(), buffer.begin() + count);
                continue;
            }
            if (count == 0) throw std::runtime_error("XREAL One IMU connection closed");
            if (errno == EAGAIN || errno == EWOULDBLOCK) return false;
            if (errno == EINTR) continue;
            throw std::runtime_error(std::string("recv failed: ") + std::strerror(errno));
        }
    }

    void close() {
        if (fd_ >= 0) {
            ::shutdown(fd_, SHUT_RDWR);
            ::close(fd_);
            fd_ = -1;
        }
        pending_.clear();
    }

private:
    bool try_parse(XrealOneSample& out) {
        while (true) {
            auto header = std::search(pending_.begin(), pending_.end(), kHeader.begin(), kHeader.end());
            if (header == pending_.end()) {
                if (pending_.size() > kHeader.size() - 1)
                    pending_.erase(pending_.begin(), pending_.end() - static_cast<std::ptrdiff_t>(kHeader.size() - 1));
                return false;
            }
            if (header != pending_.begin()) pending_.erase(pending_.begin(), header);
            if (pending_.size() < kFrameSize) return false;

            const bool marker_found = std::search(pending_.begin(), pending_.begin() + static_cast<std::ptrdiff_t>(kFrameSize),
                                                  kMarker.begin(), kMarker.end()) != pending_.begin() + static_cast<std::ptrdiff_t>(kFrameSize);
            if (!marker_found) {
                pending_.erase(pending_.begin(), pending_.begin() + static_cast<std::ptrdiff_t>(kHeader.size()));
                continue;
            }

            XrealOneSample decoded{};
            if (!decode_frame(pending_.data(), decoded)) {
                pending_.erase(pending_.begin(), pending_.begin() + static_cast<std::ptrdiff_t>(kHeader.size()));
                continue;
            }
            pending_.erase(pending_.begin(), pending_.begin() + static_cast<std::ptrdiff_t>(kFrameSize));
            out = decoded;
            return true;
        }
    }

    static bool decode_frame(const std::uint8_t* frame, XrealOneSample& out) {
        const float gx = read_le<float>(frame + 34);
        const float gy = read_le<float>(frame + 38);
        const float gz = read_le<float>(frame + 42);
        const float ax = read_le<float>(frame + 46);
        const float ay = read_le<float>(frame + 50);
        const float az = read_le<float>(frame + 54);
        const std::array<float, 6> values = {gx, gy, gz, ax, ay, az};
        if (std::any_of(values.begin(), values.end(), [](float v) { return !std::isfinite(v); })) return false;
        const float accel_norm = std::sqrt(ax * ax + ay * ay + az * az);
        if (accel_norm < 5.0F || accel_norm > 15.0F) return false;
        if (std::fabs(gx) > 1000.0F || std::fabs(gy) > 1000.0F || std::fabs(gz) > 1000.0F) return false;

        out.timestamp_nanos = read_le<std::uint64_t>(frame + 14);
        out.acceleration = {-ax, -az, -ay};
        out.gyro = {-gx, -gz, -gy};
        return true;
    }

    int fd_ = -1;
    std::vector<std::uint8_t> pending_;
};

XrealOneTcpImuSession* one_session(jlong handle) {
    return reinterpret_cast<XrealOneTcpImuSession*>(handle);
}

void throw_illegal_state(JNIEnv* env, const char* message) {
    __android_log_print(ANDROID_LOG_ERROR, "ArGlassNative", "%s", message);
    const auto exception = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(exception, message);
}

jbyteArray to_sample_array(JNIEnv* env, const XrealOneSample& sample) {
    std::array<std::uint8_t, kSampleSize> bytes{};
    write_bytes(bytes.data(), 0, &sample.timestamp_nanos, sizeof(sample.timestamp_nanos));
    write_bytes(bytes.data(), 8, sample.acceleration.data(), sample.acceleration.size() * sizeof(float));
    write_bytes(bytes.data(), 20, sample.gyro.data(), sample.gyro.size() * sizeof(float));
    const std::int32_t version = 1;
    write_bytes(bytes.data(), 32, &version, sizeof(version));
    auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_taowen_arglass_NativeBridge_createXrealOneTcpImuSession(
        JNIEnv* env, jobject, jstring host, jint port, jint connect_timeout_ms, jint read_timeout_ms) {
    const char* host_chars = env->GetStringUTFChars(host, nullptr);
    if (!host_chars) return 0;
    try {
        auto* session = new XrealOneTcpImuSession(host_chars, port, connect_timeout_ms, read_timeout_ms);
        env->ReleaseStringUTFChars(host, host_chars);
        return reinterpret_cast<jlong>(session);
    } catch (const std::exception& error) {
        env->ReleaseStringUTFChars(host, host_chars);
        throw_illegal_state(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_taowen_arglass_NativeBridge_xrealOneReadImu(JNIEnv* env, jobject, jlong handle) {
    if (!handle) return nullptr;
    try {
        XrealOneSample sample{};
        if (!one_session(handle)->next(sample)) return nullptr;
        return to_sample_array(env, sample);
    } catch (const std::exception& error) {
        throw_illegal_state(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_taowen_arglass_NativeBridge_closeXrealOneTcpImuSession(JNIEnv*, jobject, jlong handle) {
    if (!handle) return;
    delete one_session(handle);
}
