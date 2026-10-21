/*
 * v2video 转码 JNI 桥。
 *
 * 职责只有一件事：把 Java 侧 WmvTranscoder 的调用转成 libv2v_android.so 里的
 * C ABI（v2v_transcode / v2v_last_error / v2v_hw_supported）。
 *
 * 为什么走 dlopen 而不是直接链接：libv2v_android.so 是 Rust 侧独立构建的产物，
 * 桥只按符号名取函数 —— 内核升级（重编 Rust）不需要动这个桥；
 * 库缺失时表现为「转码功能不可用」，游戏照常能玩（走 rfvp 原生 WMV 路径）。
 *
 * 编译（NDK r28，见 tools/build-v2v-so.sh）：
 *   $TC/clang -shared -fPIC -O2 --target=aarch64-linux-android26 \
 *       -I$SYSROOT/usr/include v2v_bridge.c -o libv2vjni.so -ldl -llog
 *
 * 线程模型：nativeTranscode 是阻塞调用（一个 OP 约 10~40 秒），
 * Java 侧保证只在单个后台线程里调它。
 */

#include <jni.h>
#include <stdint.h>
#include <dlfcn.h>

/* 本文件按 C 编译，走 (*env)-> 调用约定；日志原型手写（与 rfvp_bridge.c 一致）。 */
int __android_log_print(int prio, const char* tag, const char* fmt, ...);
#define LOGI(...) __android_log_print(4 /*INFO*/,  "v2vbridge", __VA_ARGS__)
#define LOGE(...) __android_log_print(6 /*ERROR*/, "v2vbridge", __VA_ARGS__)

/* libv2v_android.so 的 C ABI（见 v2video/crates/android-encode/src/capi.rs）。 */
typedef int32_t (*v2v_transcode_fn)(const char* input, const char* output,
                                    uint32_t video_bitrate, uint32_t audio_bitrate);
typedef const char* (*v2v_last_error_fn)(void);
typedef int32_t (*v2v_hw_supported_fn)(void);

static void* g_lib_handle;
static v2v_transcode_fn g_transcode;
static v2v_last_error_fn g_last_error;
static v2v_hw_supported_fn g_hw_supported;
static int g_api_loaded;

static void load_api_once(void) {
    if (g_api_loaded) return;
    g_api_loaded = 1;

    /* jniLibs 里的同名 .so，dlopen 会命中本进程 native 库目录
     * （与 rfvp_bridge.c 加载 librfvp.so 的做法一致）。 */
    g_lib_handle = dlopen("libv2v_android.so", RTLD_NOW);
    if (g_lib_handle == 0) {
        LOGE("dlopen libv2v_android.so failed: %s", dlerror());
        return;
    }
    g_transcode    = (v2v_transcode_fn)    dlsym(g_lib_handle, "v2v_transcode");
    g_last_error   = (v2v_last_error_fn)   dlsym(g_lib_handle, "v2v_last_error");
    g_hw_supported = (v2v_hw_supported_fn) dlsym(g_lib_handle, "v2v_hw_supported");

    if (g_transcode == 0) {
        LOGE("dlsym v2v_transcode failed: %s", dlerror());
        g_transcode = 0;
        dlclose(g_lib_handle);
        g_lib_handle = 0;
        return;
    }
    LOGI("libv2v_android.so loaded, transcode ready");
}

static int api_ready(void) {
    load_api_once();
    return g_transcode != 0;
}

JNIEXPORT jint JNICALL
Java_com_yuki_yukihub_fvp_WmvTranscoder_nativeHwSupported(JNIEnv* env, jclass clazz) {
    (void) env; (void) clazz;
    if (!api_ready()) return 0;
    return g_hw_supported ? g_hw_supported() : 0;
}

JNIEXPORT jstring JNICALL
Java_com_yuki_yukihub_fvp_WmvTranscoder_nativeLastError(JNIEnv* env, jclass clazz) {
    (void) clazz;
    if (!api_ready() || g_last_error == 0) return (*env)->NewStringUTF(env, "");
    const char* msg = g_last_error();
    if (msg == 0) return (*env)->NewStringUTF(env, "");
    return (*env)->NewStringUTF(env, msg);
}

/**
 * 转码一个视频文件。返回码与 C ABI 一致：0 成功 / 1 参数非法 / 2 无硬件后端 / 3 转码失败。
 *
 * ⚠️ 这是阻塞调用（一个 OP 约 10~40 秒），只允许在后台线程调用。
 */
JNIEXPORT jint JNICALL
Java_com_yuki_yukihub_fvp_WmvTranscoder_nativeTranscode(JNIEnv* env, jclass clazz,
        jstring input, jstring output, jint video_bitrate, jint audio_bitrate) {
    (void) clazz;
    if (!api_ready()) {
        LOGE("native bridge not ready, skip transcode");
        return 2;
    }
    if (input == 0 || output == 0) return 1;

    const char* in  = (*env)->GetStringUTFChars(env, input, 0);
    const char* out = (*env)->GetStringUTFChars(env, output, 0);
    if (in == 0 || out == 0) {
        if (in  != 0) (*env)->ReleaseStringUTFChars(env, input, in);
        if (out != 0) (*env)->ReleaseStringUTFChars(env, output, out);
        return 1;
    }

    int32_t rc = g_transcode(in, out, (uint32_t) video_bitrate, (uint32_t) audio_bitrate);
    if (rc != 0 && g_last_error != 0) {
        const char* msg = g_last_error();
        LOGE("v2v_transcode(%s) rc=%d: %s", out, rc, msg ? msg : "?");
    }

    (*env)->ReleaseStringUTFChars(env, input, in);
    (*env)->ReleaseStringUTFChars(env, output, out);
    return (jint) rc;
}
