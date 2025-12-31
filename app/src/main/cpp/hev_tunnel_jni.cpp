#include <jni.h>
#include <pthread.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string>
#include <atomic>
#include <unistd.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "hev_jni", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "hev_jni", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "hev_jni", __VA_ARGS__)

static void* g_lib = nullptr;

// API per docs:
// int hev_socks5_tunnel_main_from_str(const unsigned char* config_str,
//                                    unsigned int config_len, int tun_fd);
// int hev_socks5_tunnel_main_from_file(const char* config_path, int tun_fd);
// void hev_socks5_tunnel_quit(void);

using main_from_str_t  = int (*)(const unsigned char*, unsigned int, int);
using main_from_file_t = int (*)(const char*, int);
using quit_t           = void (*)();

static main_from_str_t  g_main_from_str  = nullptr;
static main_from_file_t g_main_from_file = nullptr;
static quit_t           g_quit           = nullptr;

static pthread_t g_thread{};
static std::atomic<bool> g_running{false};
static std::atomic<int>  g_last_rc{-9999};

static std::string g_cfg;
static int g_tun_fd = -1;

static void ensureLoaded() {
    if (g_lib) return;

    const char* libname = "libhev-socks5-tunnel.so";
    LOGI("ensureLoaded: dlopen(%s)...", libname);

    dlerror();
    g_lib = dlopen(libname, RTLD_NOW);
    if (!g_lib) {
        const char* e = dlerror();
        LOGE("ensureLoaded: dlopen failed: %s", (e ? e : "(null)"));
        return;
    }
    LOGI("ensureLoaded: dlopen OK handle=%p", g_lib);

    dlerror();
    g_main_from_str = (main_from_str_t)dlsym(g_lib, "hev_socks5_tunnel_main_from_str");
    const char* e1 = dlerror();
    if (!g_main_from_str || e1) {
        LOGE("ensureLoaded: dlsym(main_from_str) failed: %s", (e1 ? e1 : "(null)"));
        g_main_from_str = nullptr;
    } else {
        LOGI("ensureLoaded: dlsym(main_from_str) OK ptr=%p", (void*)g_main_from_str);
    }

    dlerror();
    g_main_from_file = (main_from_file_t)dlsym(g_lib, "hev_socks5_tunnel_main_from_file");
    const char* e2 = dlerror();
    if (!g_main_from_file || e2) {
        LOGE("ensureLoaded: dlsym(main_from_file) failed: %s", (e2 ? e2 : "(null)"));
        g_main_from_file = nullptr;
    } else {
        LOGI("ensureLoaded: dlsym(main_from_file) OK ptr=%p", (void*)g_main_from_file);
    }

    dlerror();
    g_quit = (quit_t)dlsym(g_lib, "hev_socks5_tunnel_quit");
    const char* e3 = dlerror();
    if (!g_quit || e3) {
        LOGE("ensureLoaded: dlsym(quit) failed: %s", (e3 ? e3 : "(null)"));
        g_quit = nullptr;
    } else {
        LOGI("ensureLoaded: dlsym(quit) OK ptr=%p", (void*)g_quit);
    }
}

static void* hev_thread_main(void*) {
    LOGI("hev_thread_main: ENTER tid=%ld tun_fd=%d", (long)gettid(), g_tun_fd);

    if (!g_main_from_str) {
        LOGE("hev_thread_main: g_main_from_str == null");
        g_last_rc.store(-9001);
        g_running.store(false);
        return nullptr;
    }
    if (g_tun_fd < 0) {
        LOGE("hev_thread_main: invalid tun fd");
        g_last_rc.store(-9002);
        g_running.store(false);
        return nullptr;
    }

    g_last_rc.store(-9999);

    LOGI("hev_thread_main: calling main_from_str len=%u fd=%d",
         (unsigned int)g_cfg.size(), g_tun_fd);
    LOGD("hev_thread_main: cfg=\n%s", g_cfg.c_str());

    int rc = g_main_from_str(
            (const unsigned char*)g_cfg.data(),
            (unsigned int)g_cfg.size(),
            g_tun_fd
    );

    g_last_rc.store(rc);
    LOGE("hev_thread_main: main_from_str returned rc=%d", rc);

    g_running.store(false);
    LOGI("hev_thread_main: EXIT");
    return nullptr;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_myv2rayapp_service_HevTunnel_nativeStartFromString(
        JNIEnv* env, jobject,
        jstring cfg, jint tunFd) {

    ensureLoaded();

    if (!g_lib || !g_main_from_str) {
        LOGE("nativeStartFromString: HEV not loaded (g_lib=%p main=%p)", g_lib, (void*)g_main_from_str);
        g_last_rc.store(-10);
        return (jint)-10;
    }

    if (g_running.load()) {
        LOGI("nativeStartFromString: already running -> OK");
        return (jint)0;
    }

    const char* cstr = env->GetStringUTFChars(cfg, nullptr);
    if (!cstr) {
        g_last_rc.store(-12);
        return (jint)-12;
    }
    g_cfg.assign(cstr);
    env->ReleaseStringUTFChars(cfg, cstr);

    g_tun_fd = (int)tunFd;

    g_last_rc.store(-9999);
    g_running.store(true);

    int err = pthread_create(&g_thread, nullptr, hev_thread_main, nullptr);
    if (err != 0) {
        g_running.store(false);
        g_last_rc.store(-11);
        LOGE("nativeStartFromString: pthread_create failed err=%d", err);
        return (jint)-11;
    }

    LOGI("nativeStartFromString: thread created OK");
    return (jint)0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_myv2rayapp_service_HevTunnel_nativeStop(
        JNIEnv*, jobject) {

    ensureLoaded();
    LOGI("nativeStop: ENTER");

    if (!g_lib || !g_quit) {
        LOGE("nativeStop: HEV not ready (g_lib=%p quit=%p)", g_lib, (void*)g_quit);
        return (jint)-20;
    }

    if (!g_running.load()) {
        LOGI("nativeStop: not running -> OK");
        return (jint)0;
    }

    LOGI("nativeStop: calling quit()...");
    g_quit();

    LOGI("nativeStop: pthread_join...");
    pthread_join(g_thread, nullptr);

    g_running.store(false);
    LOGI("nativeStop: EXIT");
    return (jint)0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_myv2rayapp_service_HevTunnel_nativeLastRc(
        JNIEnv*, jobject) {
    int v = g_last_rc.load();
    LOGD("nativeLastRc: %d", v);
    return (jint)v;
}
