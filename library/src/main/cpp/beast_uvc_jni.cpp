#include <jni.h>
#include <libusb.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

namespace {
constexpr uint8_t kStreamingInterface = 1;
constexpr uint8_t kEndpoint = 0x81;
// Linux uvcvideo uses a five-URB ring for this camera. Keeping the same depth
// avoids out-of-order USBFS completions observed with a larger Android ring.
constexpr int kTransfers = 5;
constexpr int kPacketsPerTransfer = 32;

struct Uvc {
    libusb_context* context = nullptr;
    libusb_device_handle* handle = nullptr;
    std::atomic<bool> running{false};
    std::thread eventThread;
    std::vector<libusb_transfer*> transfers;
    std::mutex frameMutex;
    std::condition_variable frameReady;
    std::vector<uint8_t> assembling;
    std::deque<std::vector<uint8_t>> frames;
    int fid = -1;
    uint32_t pts = 0;
    bool havePts = false;
    bool frameError = false;
    int packetSize = 0;
    int altSetting = 0;
};

Uvc* from(jlong p) { return reinterpret_cast<Uvc*>(static_cast<intptr_t>(p)); }
uint32_t le32(const uint8_t* p) { return p[0] | p[1] << 8 | p[2] << 16 | p[3] << 24; }
void put32(uint8_t* p, uint32_t v) { p[0]=v; p[1]=v>>8; p[2]=v>>16; p[3]=v>>24; }

void finishFrame(Uvc* u) {
    // Trim UVC packet padding after the JPEG end marker. Beast fills the final
    // 2400-byte transaction, while libuvc exposes only the encoded frame.
    bool hasEoi=false;for(size_t i=u->assembling.size();i>1;--i)if(u->assembling[i-2]==0xff&&u->assembling[i-1]==0xd9){u->assembling.resize(i);hasEoi=true;break;}
    // Match libuvc: UVC FID/EOF delimit the frame. Some MJPEG devices omit
    // the final EOI marker from USB payloads even though decoders accept it.
    if (!u->frameError && hasEoi && u->assembling.size() > 4 && u->assembling[0] == 0xff && u->assembling[1] == 0xd8) {
        std::lock_guard<std::mutex> lock(u->frameMutex);
        if (u->frames.size() >= 2) u->frames.pop_front();
        u->frames.emplace_back(std::move(u->assembling));
        u->frameReady.notify_one();
    }
    u->assembling.clear();
    u->frameError=false;
}

void consumePayload(Uvc* u, const uint8_t* data, int length) {
    if (length < 2) return;
    const int header = data[0];
    if (header < 2 || header > length) return;
    const uint8_t flags = data[1];
    if (flags & 0x40) { u->assembling.clear(); u->frameError=true; return; } // UVC payload error
    const int fid = flags & 1;
    const bool hasPts=(flags&0x04)&&header>=6;const uint32_t pts=hasPts?le32(data+2):0;
    if ((u->fid >= 0 && fid != u->fid) || (hasPts && u->havePts && pts != u->pts)) {
        // A boundary without EOF means USB data was lost. Never concatenate
        // the next JPEG onto the damaged one (FID alone wraps every 2 frames).
        u->assembling.clear();u->frameError=false;
    }
    u->fid = fid;
    if(hasPts){u->pts=pts;u->havePts=true;}
    const uint8_t* payload = data + header;
    const int payloadLength = length - header;
    if (payloadLength > 0 && u->assembling.size() + payloadLength < 8 * 1024 * 1024)
        u->assembling.insert(u->assembling.end(), payload, payload + payloadLength);
    if (flags & 2) finishFrame(u); // EOF
}

void LIBUSB_CALL isoCallback(libusb_transfer* transfer) {
    auto* u = static_cast<Uvc*>(transfer->user_data);
    if (transfer->status == LIBUSB_TRANSFER_COMPLETED) {
        for (int i=0; i<transfer->num_iso_packets; ++i) {
            const auto& packet = transfer->iso_packet_desc[i];
            if (packet.status == LIBUSB_TRANSFER_COMPLETED && packet.actual_length)
                consumePayload(u, libusb_get_iso_packet_buffer_simple(transfer, i), packet.actual_length);
            else if (packet.status != LIBUSB_TRANSFER_COMPLETED) u->frameError=true;
        }
    }
    if (u->running && transfer->status != LIBUSB_TRANSFER_NO_DEVICE) libusb_submit_transfer(transfer);
}

// Locate the MJPEG format and 1920x1080 frame indexes from class-specific VS descriptors.
bool findFormat(libusb_device_handle* handle, uint8_t& format, uint8_t& frame, uint32_t& interval,
                int& alt, int& packetSize) {
    libusb_config_descriptor* config = nullptr;
    if (libusb_get_active_config_descriptor(libusb_get_device(handle), &config) != 0) return false;
    format=0; frame=0; interval=333333; alt=0; packetSize=0;
    const auto& intf = config->interface[kStreamingInterface];
    for (int a=0; a<intf.num_altsetting; ++a) {
        const auto& setting=intf.altsetting[a];
        for (int e=0; e<setting.bNumEndpoints; ++e) {
            const auto& ep=setting.endpoint[e];
            if (ep.bEndpointAddress==kEndpoint && (ep.bmAttributes&3)==LIBUSB_TRANSFER_TYPE_ISOCHRONOUS) {
                int bytes=(ep.wMaxPacketSize&0x7ff)*(1+((ep.wMaxPacketSize>>11)&3));
                // libuvc selects the smallest alternate setting that can carry
                // the negotiated payload. Beast negotiates 2400 bytes and the
                // official SDK selects alt 5 (raw wMaxPacketSize 0x1320).
                if(bytes==2400){packetSize=bytes;alt=setting.bAlternateSetting;}
                else if(!alt && bytes>packetSize){packetSize=bytes;alt=setting.bAlternateSetting;}
            }
        }
        const uint8_t* p=setting.extra; int left=setting.extra_length; uint8_t mjpeg=0;
        while(left>=3 && p[0]>=3 && p[0]<=left) {
            if(p[1]==0x24 && p[2]==0x06) mjpeg=p[3]; // VS_FORMAT_MJPEG
            if(p[1]==0x24 && p[2]==0x07 && mjpeg && p[0]>=26) { // VS_FRAME_MJPEG
                uint16_t w=p[5]|p[6]<<8, h=p[7]|p[8]<<8;
                if(!format && w==1920 && h==1080){format=mjpeg;frame=p[3];interval=le32(p+21);}
            }
            left-=p[0]; p+=p[0];
        }
    }
    libusb_free_config_descriptor(config);
    return format && frame && alt && packetSize;
}

bool selectAlt(libusb_device_handle* handle,int desired,int& alt,int& packetSize){
    libusb_config_descriptor* config=nullptr;if(libusb_get_active_config_descriptor(libusb_get_device(handle),&config)!=0)return false;
    int bestBytes=0,bestAlt=0;const auto& intf=config->interface[kStreamingInterface];
    for(int a=0;a<intf.num_altsetting;++a){const auto& s=intf.altsetting[a];for(int e=0;e<s.bNumEndpoints;++e){const auto& ep=s.endpoint[e];
        if(ep.bEndpointAddress!=kEndpoint||(ep.bmAttributes&3)!=LIBUSB_TRANSFER_TYPE_ISOCHRONOUS)continue;
        int bytes=(ep.wMaxPacketSize&0x7ff)*(1+((ep.wMaxPacketSize>>11)&3));
        if(bytes>=desired&&(!bestBytes||bytes<bestBytes)){bestBytes=bytes;bestAlt=s.bAlternateSetting;}
    }}
    libusb_free_config_descriptor(config);if(!bestAlt)return false;alt=bestAlt;packetSize=desired;return true;
}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_taowen_arglass_BeastCameraNative_start(JNIEnv*, jobject, jint fd) {
    auto* u=new Uvc();
    uint8_t format=0,frame=0; uint32_t interval=333333; uint8_t probe[26]{};
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
    if(libusb_init(&u->context)!=0 || libusb_wrap_sys_device(u->context, fd, &u->handle)!=0) goto fail;
    if(libusb_kernel_driver_active(u->handle,kStreamingInterface)==1) libusb_detach_kernel_driver(u->handle,kStreamingInterface);
    if(libusb_claim_interface(u->handle,kStreamingInterface)!=0) goto fail;
    if(!findFormat(u->handle,format,frame,interval,u->altSetting,u->packetSize)) goto fail_claim;
    // Exact libuvc sequence used by VITURE SDK 2.3.2 on Beast.
    libusb_control_transfer(u->handle,0xa1,0x83,0x0100,kStreamingInterface,probe,sizeof(probe),1500);
    std::memset(probe,0,sizeof(probe));probe[0]=1;probe[2]=format;probe[3]=frame;put32(probe+4,interval);
    put32(probe+18,0x003f4a4d);put32(probe+22,2400);
    if(libusb_control_transfer(u->handle,0x21,0x01,0x0100,kStreamingInterface,probe,sizeof(probe),1500)<0) goto fail_claim;
    if(libusb_control_transfer(u->handle,0xa1,0x81,0x0100,kStreamingInterface,probe,sizeof(probe),1500)<26) goto fail_claim;
    // Keep the endpoint transaction capacity selected above. dwMaxPayload is
    // a negotiation result, not an instruction to resize USBFS iso packets.
    if(!selectAlt(u->handle,static_cast<int>(le32(probe+22)),u->altSetting,u->packetSize))goto fail_claim;
    if(libusb_control_transfer(u->handle,0x21,0x01,0x0200,kStreamingInterface,probe,sizeof(probe),1500)<0) goto fail_claim;
    if(libusb_set_interface_alt_setting(u->handle,kStreamingInterface,u->altSetting)!=0) goto fail_claim;
    u->running=true;
    for(int n=0;n<kTransfers;++n){
        auto* t=libusb_alloc_transfer(kPacketsPerTransfer); if(!t) goto fail_running;
        auto* buffer=new uint8_t[u->packetSize*kPacketsPerTransfer];
        libusb_fill_iso_transfer(t,u->handle,kEndpoint,buffer,u->packetSize*kPacketsPerTransfer,kPacketsPerTransfer,isoCallback,u,1000);
        libusb_set_iso_packet_lengths(t,u->packetSize); u->transfers.push_back(t);
        if(libusb_submit_transfer(t)!=0) goto fail_running;
    }
    u->eventThread=std::thread([u]{while(u->running){timeval tv{0,100000};libusb_handle_events_timeout_completed(u->context,&tv,nullptr);}});
    return static_cast<jlong>(reinterpret_cast<intptr_t>(u));
fail_running: u->running=false;
    for(auto* t:u->transfers)libusb_cancel_transfer(t);
    for(int i=0;i<4;++i){timeval tv{0,50000};libusb_handle_events_timeout_completed(u->context,&tv,nullptr);}
    for(auto* t:u->transfers){delete[] t->buffer;libusb_free_transfer(t);} u->transfers.clear();
fail_claim: libusb_release_interface(u->handle,kStreamingInterface);
fail: if(u->handle)libusb_close(u->handle);if(u->context)libusb_exit(u->context);delete u;return 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_taowen_arglass_BeastCameraNative_readFrame(JNIEnv* env,jobject,jlong p) {
    auto* u=from(p);if(!u)return nullptr;std::unique_lock<std::mutex> lock(u->frameMutex);
    u->frameReady.wait_for(lock,std::chrono::milliseconds(1200),[u]{return !u->frames.empty()||!u->running;});
    if(u->frames.empty())return nullptr;auto frame=std::move(u->frames.front());u->frames.pop_front();lock.unlock();
    auto out=env->NewByteArray(frame.size());env->SetByteArrayRegion(out,0,frame.size(),reinterpret_cast<jbyte*>(frame.data()));return out;
}

extern "C" JNIEXPORT void JNICALL Java_com_taowen_arglass_BeastCameraNative_stop(JNIEnv*,jobject,jlong p) {
    auto* u=from(p);if(!u)return;u->running=false;
    for(auto* t:u->transfers)libusb_cancel_transfer(t);
    if(u->eventThread.joinable())u->eventThread.join();
    for(int i=0;i<4;++i){timeval tv{0,50000};libusb_handle_events_timeout_completed(u->context,&tv,nullptr);}
    for(auto* t:u->transfers){delete[] t->buffer;libusb_free_transfer(t);}
    libusb_set_interface_alt_setting(u->handle,kStreamingInterface,0);libusb_release_interface(u->handle,kStreamingInterface);
    libusb_close(u->handle);libusb_exit(u->context);u->frameReady.notify_all();delete u;
}
