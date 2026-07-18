#include <jni.h>
#include <dlfcn.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {
struct Size2i { int32_t width; int32_t height; };
struct RgbFrame { int32_t format; int32_t padding; uint64_t timestamp; Size2i resolution; uint64_t size; const void* data; };
static_assert(sizeof(RgbFrame)==0x28);
struct Session {
    void* plugin=nullptr; void* loader=nullptr; uint64_t callbackHandle=0;
    std::mutex mutex; std::vector<uint8_t> frame; int width=0; int height=0;
    std::atomic<bool> active{false};
    bool (*stopCapture)(uint64_t)=nullptr; void (*pauseSession)()=nullptr; void (*destroySession)()=nullptr;
};
std::mutex errorMutex; std::string lastError;
void setError(const char* value){std::lock_guard<std::mutex> lock(errorMutex);lastError=value?value:"unknown error";}
Session* current=nullptr;
int getGraphicContextType(void*){return 2;} struct GraphicInterface{decltype(&getGraphicContextType) getType;}; GraphicInterface graphic{getGraphicContextType};
void* getUnityInterface(const void*){static std::atomic<unsigned> calls{0};return calls++==0?nullptr:&graphic;}
struct UnityInterfaces{decltype(&getUnityInterface) getInterface;}; UnityInterfaces unity{getUnityInterface};
void controlCallback(uint64_t,uint64_t,uint64_t,void*){}
void rgbCallback(RgbFrame frame,void*){
    Session* s=current;if(!s||!s->active||!frame.data||frame.size==0||frame.format!=2)return;
    const uint64_t expected=static_cast<uint64_t>(frame.resolution.width)*frame.resolution.height*3;
    if(frame.size<expected||expected>64*1024*1024)return;
    std::lock_guard<std::mutex> lock(s->mutex);s->width=frame.resolution.width;s->height=frame.resolution.height;
    s->frame.assign(static_cast<const uint8_t*>(frame.data),static_cast<const uint8_t*>(frame.data)+expected);
}
template<class T>T symbol(void* library,const char* name){return reinterpret_cast<T>(dlsym(library,name));}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_taowen_arglass_XrealEyeOfficialNative_start(JNIEnv* env,jobject,jobject activity){
    if(current){setError("official camera is already active");return 0;}
    auto* s=new Session();JavaVM* vm=nullptr;env->GetJavaVM(&vm);
    s->loader=dlopen("libnr_loader.so",RTLD_NOW|RTLD_GLOBAL);if(!s->loader){setError(dlerror());delete s;return 0;}
    using OnLoad=jint(*)(JavaVM*,void*);auto loaderOnLoad=symbol<OnLoad>(s->loader,"JNI_OnLoad");
    if(!loaderOnLoad||loaderOnLoad(vm,nullptr)<JNI_VERSION_1_6){setError("libnr_loader JNI_OnLoad failed");dlclose(s->loader);delete s;return 0;}
    s->plugin=dlopen("libXREALXRPlugin.so",RTLD_NOW|RTLD_LOCAL);if(!s->plugin){setError(dlerror());dlclose(s->loader);delete s;return 0;}
    auto pluginOnLoad=symbol<OnLoad>(s->plugin,"JNI_OnLoad");
    using InitActivity=void(*)(void*);using InitControl=void(*)(decltype(&controlCallback),void*);using PluginLoad=void(*)(void*);
    using Create=bool(*)(bool);using Resume=void(*)();using SetFormat=void(*)(int);using Start=uint64_t(*)(decltype(&rgbCallback),void*);
    auto initActivity=symbol<InitActivity>(s->plugin,"InitUnityActivity");auto initControl=symbol<InitControl>(s->plugin,"InitGlassesControl");
    auto pluginLoad=symbol<PluginLoad>(s->plugin,"UnityPluginLoad");auto create=symbol<Create>(s->plugin,"CreateSession");
    auto resume=symbol<Resume>(s->plugin,"ResumeSession");auto setFormat=symbol<SetFormat>(s->plugin,"SetRGBCameraImageFormat");
    auto start=symbol<Start>(s->plugin,"StartRGBCameraDataCapture");s->stopCapture=symbol<bool(*)(uint64_t)>(s->plugin,"StopRGBCameraDataCapture");
    s->pauseSession=symbol<void(*)()>(s->plugin,"PauseSession");s->destroySession=symbol<void(*)()>(s->plugin,"DestroySession");
    if(!pluginOnLoad||pluginOnLoad(vm,nullptr)<JNI_VERSION_1_6||!initActivity||!initControl||!pluginLoad||!create||!resume||!setFormat||!start||!s->stopCapture){
        setError("missing XREAL official RGB camera export");dlclose(s->plugin);dlclose(s->loader);delete s;return 0;
    }
    initActivity(activity);initControl(controlCallback,nullptr);pluginLoad(&unity);
    if(!create(false)){setError("XREAL CreateSession(false) failed");dlclose(s->plugin);dlclose(s->loader);delete s;return 0;}
    std::this_thread::sleep_for(std::chrono::seconds(3));resume();setFormat(2);s->active=true;current=s;
    s->callbackHandle=start(rgbCallback,nullptr);if(!s->callbackHandle){current=nullptr;s->active=false;setError("StartRGBCameraDataCapture returned 0");if(s->destroySession)s->destroySession();dlclose(s->plugin);dlclose(s->loader);delete s;return 0;}
    return reinterpret_cast<jlong>(s);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_taowen_arglass_XrealEyeOfficialNative_readFrame(JNIEnv* env,jobject,jlong handle){
    auto* s=reinterpret_cast<Session*>(handle);if(!s)return nullptr;std::lock_guard<std::mutex> lock(s->mutex);if(s->frame.empty())return nullptr;
    auto out=env->NewByteArray(s->frame.size());env->SetByteArrayRegion(out,0,s->frame.size(),reinterpret_cast<const jbyte*>(s->frame.data()));s->frame.clear();return out;
}
extern "C" JNIEXPORT jint JNICALL Java_com_taowen_arglass_XrealEyeOfficialNative_width(JNIEnv*,jobject,jlong h){auto* s=reinterpret_cast<Session*>(h);return s?s->width:0;}
extern "C" JNIEXPORT jint JNICALL Java_com_taowen_arglass_XrealEyeOfficialNative_height(JNIEnv*,jobject,jlong h){auto* s=reinterpret_cast<Session*>(h);return s?s->height:0;}
extern "C" JNIEXPORT void JNICALL Java_com_taowen_arglass_XrealEyeOfficialNative_stop(JNIEnv*,jobject,jlong handle){
    auto* s=reinterpret_cast<Session*>(handle);if(!s)return;s->active=false;current=nullptr;if(s->callbackHandle)s->stopCapture(s->callbackHandle);if(s->pauseSession)s->pauseSession();if(s->destroySession)s->destroySession();dlclose(s->plugin);dlclose(s->loader);delete s;
}
extern "C" JNIEXPORT jstring JNICALL Java_com_taowen_arglass_XrealEyeOfficialNative_lastError(JNIEnv* env,jobject){std::lock_guard<std::mutex> lock(errorMutex);return env->NewStringUTF(lastError.c_str());}
