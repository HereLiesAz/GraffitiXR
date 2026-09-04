#include <jni.h>
#include <shared_mutex>
#include <android/log.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <opencv2/opencv.hpp>
#include <GLES3/gl3.h>
#include <signal.h>
#include <unwind.h>
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/prctl.h>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <exception>
#include "include/MobileGS.h"
#include "include/HomographyTracker.h"

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "GraffitiJNI", __VA_ARGS__)

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GraffitiJNI", __VA_ARGS__)

MobileGS* gSlamEngine = nullptr;
// Guards every read of gSlamEngine (the null-check-then-use pattern every JNI entry point below
// does) and its deletion in nativeDestroy. Without this, nativeDestroy's `delete gSlamEngine` races
// every other entry point that dereferences the same raw pointer with no lock of its own --
// SlamManager.kt's destroy() holds initLock, but feedYuvFrame/updateCamera/getRelocResult/etc do
// not, and Kotlin has no way to serialize against destroy() from those call sites.
//
// This only protects the *pointer's* lifetime, not MobileGS's internal state -- that's MobileGS's
// own job (mMutex/mRelocMutex/std::atomic members; see include/MobileGS.h). So it's a
// shared_mutex: nativeInitialize/nativeDestroy (which write the pointer itself) take an exclusive
// std::unique_lock; every entry point that only dereferences it takes a std::shared_lock and can
// run concurrently with other readers -- that's the actual fix for heavy OpenCV/SuperPoint work
// (fingerprint generation, relocalization) blocking the per-frame camera/render path behind one
// global lock. Three more entry points also need std::unique_lock despite not touching the
// pointer: nativeLoadSuperPoint/nativeLoadDistortionHead/nativeLoadLowLightEnhancer mutate
// mSuperPoint/mDistortionHead/mEnhancer in place via their load() calls.
//
// CORRECTION: an earlier version of this comment claimed gEngineMutex was "the only thing"
// serializing those reloads against an in-flight detect()/enhance()/run() -- false, and dangerous
// to believe. relocThreadFunc's background worker (MobileGS.cpp) calls runRelocPass/
// tryUpdateFingerprint, which call into SuperPointDetector/DistortionHead/LowLightEnhancer,
// WITHOUT ever taking gEngineMutex -- it's a std::thread, not a JNI entry point. What actually
// makes a live reload safe against that thread is each of those three classes' own internal
// std::mutex (SuperPointDetector.h/DistortionHead.h/LowLightEnhancer.h each declare `mMutex`,
// taken in both load() and the inference call). gEngineMutex's exclusive lock on the three loader
// entry points is still correct and still necessary -- it's what protects them against the OTHER
// JNI-entry readers (getSuperPointFeatures, getFingerprintKeypoints, generateFingerprint, all
// called only via JNI) -- it just isn't sufficient on its own, and the reloc-thread path was never
// covered by it in the first place.
//
// tools/check_native_locking.py finds gaps like the load-vs-JNI-reader one, but it only parses
// MobileGS.h/MobileGS.cpp member declarations -- it cannot see JNI-level globals (gLastColorFrame
// below is exactly such a case: written by both nativeFeedYuvFrame and nativeFeedColorFrame, which
// now both take only a shared_lock, so it needs its own mutex -- see gColorFrameMutex) or classes
// declared elsewhere (SuperPointDetector etc., which is how it also can't see the mMutex that
// actually protects the load-vs-reloc-thread race above). Don't treat a clean run of that script as
// proof gEngineMutex's shared/exclusive split is sufficient by itself anywhere in this file.
std::shared_mutex gEngineMutex;
cv::Mat gLastColorFrame; // MANDATE: Kept in Sensor-Native (Landscape) orientation
// gLastColorFrame is written by BOTH nativeFeedYuvFrame and nativeFeedColorFrame, which (since the
// shared_mutex conversion above) both take only gEngineMutex's shared_lock and so can now run
// concurrently -- e.g. the normal camera feed on the GL thread racing a glasses-session feed on
// its own coroutine (see ArViewModel.startGlassesSession/SlamManager's forwardFrame). The old
// exclusive std::mutex incidentally serialized these two writers; nothing does now except this
// dedicated mutex. Lock it only around the read-modify-write of gLastColorFrame itself (assign,
// then take a cheap header-copy snapshot), never around the heavy YUV/RGBA conversion or the
// reloc-frame build that follows -- those must stay outside the lock or this reintroduces the
// stall the shared_mutex conversion exists to remove.
std::mutex gColorFrameMutex;
JavaVM* gJvm = nullptr;

// ARCore-unavailable fallback (see HomographyTracker.h). Entirely independent of gSlamEngine —
// no shared state, no lifecycle coupling — and internally mutex-guarded, so unlike gSlamEngine it
// needs neither gEngineMutex nor an explicit destroy: a single process-lifetime instance is fine.
HomographyTracker gHomographyTracker;

// ── Native crash capture ─────────────────────────────────────────────────────
// A SIGSEGV/SIGABRT in the AR/SLAM native code kills the process before the JVM
// UncaughtExceptionHandler can run, so the Kotlin CrashReporter never sees it
// ("crashed hard, nothing on screen"). This installs a signal handler that writes
// the native backtrace to a file, then chains to the previous handler so the
// normal tombstone still happens. MainActivity surfaces the file on next launch.
namespace {
struct BtState { void** cur; void** end; };
_Unwind_Reason_Code btCb(struct _Unwind_Context* ctx, void* arg) {
    BtState* s = static_cast<BtState*>(arg);
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc) {
        if (s->cur == s->end) return _URC_END_OF_STACK;
        *s->cur++ = reinterpret_cast<void*>(pc);
    }
    return _URC_NO_REASON;
}
char gCrashPath[1024] = {0};
char gAltStack[64 * 1024];
struct sigaction gOldSegv{}, gOldAbort{}, gOldBus{}, gOldIll{}, gOldFpe{};
void chainOld(int sig, siginfo_t* info, void* uc) {
    struct sigaction* old = nullptr;
    switch (sig) {
        case SIGSEGV: old = &gOldSegv; break;
        case SIGABRT: old = &gOldAbort; break;
        case SIGBUS:  old = &gOldBus;  break;
        case SIGILL:  old = &gOldIll;  break;
        case SIGFPE:  old = &gOldFpe;  break;
        default: break;
    }
    if (old && (old->sa_flags & SA_SIGINFO) && old->sa_sigaction) {
        old->sa_sigaction(sig, info, uc);
    } else if (old && old->sa_handler != SIG_DFL && old->sa_handler != SIG_IGN) {
        old->sa_handler(sig);
    } else {
        signal(sig, SIG_DFL);
        raise(sig);
    }
}
// ── async-signal-safe formatting helpers ─────────────────────────────────────
// The handler runs inside SIGSEGV: no stdio, no heap, no locale (fopen/fprintf/strsignal all
// allocate or lock — a crash inside malloc would deadlock the handler). Everything below
// appends into a caller-provided buffer using only stack arithmetic.
size_t appendStr(char* buf, size_t pos, size_t cap, const char* s) {
    while (s && *s && pos < cap - 1) buf[pos++] = *s++;
    return pos;
}
size_t appendDec(char* buf, size_t pos, size_t cap, long v) {
    char tmp[24]; size_t n = 0;
    bool neg = v < 0; unsigned long u = neg ? static_cast<unsigned long>(-v) : static_cast<unsigned long>(v);
    do { tmp[n++] = static_cast<char>('0' + (u % 10)); u /= 10; } while (u && n < sizeof(tmp));
    if (neg && pos < cap - 1) buf[pos++] = '-';
    while (n && pos < cap - 1) buf[pos++] = tmp[--n];
    return pos;
}
size_t appendHex(char* buf, size_t pos, size_t cap, uintptr_t v) {
    pos = appendStr(buf, pos, cap, "0x");
    char tmp[2 * sizeof(uintptr_t)]; size_t n = 0;
    do { unsigned d = v & 0xF; tmp[n++] = static_cast<char>(d < 10 ? '0' + d : 'a' + d - 10); v >>= 4; } while (v && n < sizeof(tmp));
    while (n && pos < cap - 1) buf[pos++] = tmp[--n];
    return pos;
}
const char* sigName(int sig) {
    switch (sig) {
        case SIGSEGV: return "Segmentation fault";
        case SIGABRT: return "Abort";
        case SIGBUS:  return "Bus error";
        case SIGILL:  return "Illegal instruction";
        case SIGFPE:  return "Floating point exception";
        default:      return "Signal";
    }
}
void nativeCrashHandler(int sig, siginfo_t* info, void* uc) {
    void* frames[64];
    BtState st{frames, frames + 64};
    // _Unwind_Backtrace and dladdr are not formally async-signal-safe (loader lock), but they
    // are the entire diagnostic value of this handler and in practice safe unless the crash
    // happened inside the dynamic loader. Everything else below is strictly safe: open/write/
    // close/prctl/read only, one static line buffer, zero allocation, zero stdio.
    _Unwind_Backtrace(btCb, &st);
    size_t n = static_cast<size_t>(st.cur - frames);
    if (gCrashPath[0]) {
        int fd = open(gCrashPath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd >= 0) {
            char line[1024]; size_t p;

            p = 0;
            p = appendStr(line, p, sizeof(line), "NATIVE CRASH sig=");
            p = appendDec(line, p, sizeof(line), sig);
            p = appendStr(line, p, sizeof(line), " (");
            p = appendStr(line, p, sizeof(line), sigName(sig));
            p = appendStr(line, p, sizeof(line), ") addr=");
            p = appendHex(line, p, sizeof(line), info ? reinterpret_cast<uintptr_t>(info->si_addr) : 0);
            p = appendStr(line, p, sizeof(line), "\n");
            write(fd, line, p);

            // Attribute the crash to a process + thread. The hardware-stereo/depth probe runs in the
            // isolated ":probe" process; without this header a probe-process crash is indistinguishable
            // from a main-app crash (both share cacheDir). open/read/prctl are async-signal-safe.
            char pname[256] = {0};
            int cfd = open("/proc/self/cmdline", O_RDONLY);
            if (cfd >= 0) {
                ssize_t r = read(cfd, pname, sizeof(pname) - 1);
                if (r > 0) pname[r] = '\0';
                close(cfd);
            }
            char tname[32] = {0};
            prctl(PR_GET_NAME, tname, 0, 0, 0);
            p = 0;
            p = appendStr(line, p, sizeof(line), "process=");
            p = appendStr(line, p, sizeof(line), pname[0] ? pname : "?");
            p = appendStr(line, p, sizeof(line), " pid=");
            p = appendDec(line, p, sizeof(line), getpid());
            p = appendStr(line, p, sizeof(line), " tid=");
            p = appendDec(line, p, sizeof(line), gettid());
            p = appendStr(line, p, sizeof(line), " thread=");
            p = appendStr(line, p, sizeof(line), tname[0] ? tname : "?");
            p = appendStr(line, p, sizeof(line), "\n");
            write(fd, line, p);

            for (size_t i = 0; i < n; ++i) {
                Dl_info di;
                const char* sym = "?";
                const char* lib = "?";
                uintptr_t off = 0;
                if (dladdr(frames[i], &di)) {
                    if (di.dli_sname) sym = di.dli_sname;
                    if (di.dli_fname) lib = di.dli_fname;
                    if (di.dli_saddr) off = reinterpret_cast<uintptr_t>(frames[i]) -
                                            reinterpret_cast<uintptr_t>(di.dli_saddr);
                }
                p = 0;
                p = appendStr(line, p, sizeof(line), "#");
                if (i < 10) p = appendStr(line, p, sizeof(line), "0");
                p = appendDec(line, p, sizeof(line), static_cast<long>(i));
                p = appendStr(line, p, sizeof(line), " ");
                p = appendHex(line, p, sizeof(line), reinterpret_cast<uintptr_t>(frames[i]));
                p = appendStr(line, p, sizeof(line), " ");
                p = appendStr(line, p, sizeof(line), sym);
                p = appendStr(line, p, sizeof(line), "+");
                p = appendDec(line, p, sizeof(line), static_cast<long>(off));
                p = appendStr(line, p, sizeof(line), " (");
                p = appendStr(line, p, sizeof(line), lib);
                p = appendStr(line, p, sizeof(line), ")\n");
                write(fd, line, p);
            }
            close(fd);
        }
    }
    chainOld(sig, info, uc);
    // If the chained handler returned instead of killing the process, the app would keep running
    // with a dead internal thread — e.g. ARCore minus its VIO worker: session resumes, zero camera
    // frames, update() wedges forever. A half-alive process is strictly worse than a dead one;
    // guarantee the default disposition.
    signal(sig, SIG_DFL);
    raise(sig);
}
} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_NativeCrashHandler_nativeInstall(
        JNIEnv* env, jobject, jstring jpath) {
    const char* p = env->GetStringUTFChars(jpath, nullptr);
    if (p) {
        strncpy(gCrashPath, p, sizeof(gCrashPath) - 1);
        env->ReleaseStringUTFChars(jpath, p);
    }
    stack_t ss;
    ss.ss_sp = gAltStack;
    ss.ss_size = sizeof(gAltStack);
    ss.ss_flags = 0;
    sigaltstack(&ss, nullptr);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = nativeCrashHandler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, &gOldSegv);
    sigaction(SIGABRT, &sa, &gOldAbort);
    sigaction(SIGBUS,  &sa, &gOldBus);
    sigaction(SIGILL,  &sa, &gOldIll);
    sigaction(SIGFPE,  &sa, &gOldFpe);
}

void bitmapToMat(JNIEnv * env, jobject bitmap, cv::Mat& dst) {
    AndroidBitmapInfo info;
    void* pixels = 0;
    // Check every AndroidBitmap_* result: a failed getInfo/lockPixels leaves `pixels` null, and
    // wrapping null in a cv::Mat then copying it is a guaranteed SIGSEGV. Also require RGBA_8888 and
    // honor info.stride (rows may be padded, so it isn't necessarily width*4).
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) return;
    cv::Mat tmp(info.height, info.width, CV_8UC4, pixels, info.stride);
    tmp.copyTo(dst);
    AndroidBitmap_unlockPixels(env, bitmap);
}

void matToBitmap(JNIEnv * env, cv::Mat& src, jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels = 0;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) return;
    cv::Mat tmp(info.height, info.width, CV_8UC4, pixels, info.stride);
    if(src.type() == CV_8UC4) {
        src.copyTo(tmp);
    } else if(src.type() == CV_8UC3) {
        cv::cvtColor(src, tmp, cv::COLOR_RGB2RGBA);
    } else if(src.type() == CV_8UC1) {
        cv::cvtColor(src, tmp, cv::COLOR_GRAY2RGBA);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeUpdateDeviceMotion(JNIEnv* env, jobject thiz, jfloatArray angularVel, jfloatArray linearVel) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) {
        // updateDeviceMotion memcpy's 3 floats out of each; a shorter array (or a null one) would
        // read past the end. Validate before pinning rather than trusting the Kotlin caller.
        if (!angularVel || !linearVel) return;
        if (env->GetArrayLength(angularVel) < 3 || env->GetArrayLength(linearVel) < 3) return;
        jfloat* a = env->GetFloatArrayElements(angularVel, nullptr);
        jfloat* l = env->GetFloatArrayElements(linearVel, nullptr);
        gSlamEngine->updateDeviceMotion(a, l);
        env->ReleaseFloatArrayElements(angularVel, a, JNI_ABORT);
        env->ReleaseFloatArrayElements(linearVel, l, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeInitialize(JNIEnv* env, jobject thiz) {
    std::unique_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine) {
        gSlamEngine = new MobileGS();
        gSlamEngine->initialize(1920, 1080);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeDestroy(JNIEnv* env, jobject thiz) {
    std::unique_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) { delete gSlamEngine; gSlamEngine = nullptr; }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetArCoreTrackingState(JNIEnv* env, jobject thiz, jboolean isTracking) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) gSlamEngine->setArCoreTrackingState(isTracking);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetViewportSize(JNIEnv* env, jobject thiz, jint width, jint height) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) gSlamEngine->setViewportSize(width, height);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetRelocEnabled(JNIEnv* env, jobject thiz, jboolean enabled) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) gSlamEngine->setRelocEnabled(enabled);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetSelfGrowEnabled(JNIEnv* env, jobject thiz, jboolean enabled) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) gSlamEngine->setSelfGrowEnabled(enabled);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetEvalRngSeed(JNIEnv* env, jobject thiz, jlong seed) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) gSlamEngine->setEvalRngSeed((long long)seed);
}

// EVALUATION.md 3.1 / IMPLEMENTATION.md 6a.4 — inline relocalization for deterministic replay.
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetEvalSyncReloc(
        JNIEnv* env, jobject thiz, jboolean enabled, jint everyN) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) gSlamEngine->setEvalSyncReloc(enabled == JNI_TRUE, (int)everyN);
}

// The cadence ACTUALLY in force: 0 when sync mode is off, so one int carries both the flag and the
// N. Read back rather than remembered on the Kotlin side, so the run-identity sidecar reports what
// the engine is doing and not what the caller asked for -- with no engine there is no sync mode, and
// a sidecar claiming otherwise would be the kind of evidence that is worse than none.
JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetEvalSyncRelocEveryN(JNIEnv* env, jobject thiz) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    return gSlamEngine ? (jint)gSlamEngine->evalSyncEveryN() : 0;
}

JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetWallKeypointCount(JNIEnv* env, jobject thiz) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    return gSlamEngine ? gSlamEngine->getWallKeypointCount() : 0;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetWallPatch(JNIEnv* env, jobject thiz, jobject bitmap) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine || !bitmap) return;
    cv::Mat img; bitmapToMat(env, bitmap, img);
    gSlamEngine->setWallPatch(img);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetWallPatchBytes(
        JNIEnv* env, jobject thiz, jbyteArray data, jint size) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine || !data || size <= 0) return;
    // 64-bit product, like every sibling restore path in this file (nativeRestoreWallFingerprint /
    // nativeRestoreWallFingerprintMetric / nativeRestoreWallFeatureMap): size*size in 32-bit jint can
    // overflow for a hostile/corrupt size and wrap past this check into a too-small buffer.
    if ((jlong)size * (jlong)size > (jlong)env->GetArrayLength(data)) return; // expect a size x size single-channel gray buffer
    jbyte* p = env->GetByteArrayElements(data, nullptr);
    cv::Mat gray(size, size, CV_8UC1, reinterpret_cast<uchar*>(p));
    gSlamEngine->setWallPatch(gray); // clones internally; safe to release after
    env->ReleaseByteArrayElements(data, p, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetMappingPaused(JNIEnv* env, jobject thiz, jboolean paused) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) gSlamEngine->setMappingPaused(paused);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeUpdateCamera(
        JNIEnv* env, jobject thiz,
        jfloatArray viewMatrix, jfloatArray projMatrix,
        jlong timestampNs) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) {
        // Both are memcpy'd as 16 floats below (and read as 4x4 by updateCamera), so a short
        // array would read past the end. Validate both before pinning either.
        if (!viewMatrix || !projMatrix) return;
        if (env->GetArrayLength(viewMatrix) < 16 || env->GetArrayLength(projMatrix) < 16) {
            return;
        }
        jfloat* view = env->GetFloatArrayElements(viewMatrix, nullptr);
        jfloat* proj = env->GetFloatArrayElements(projMatrix, nullptr);

        try {
            gSlamEngine->updateCamera(view, proj);
        } catch (const std::exception& e) {
            LOGE("nativeUpdateCamera: exception: %s", e.what());
        } catch (...) {
            LOGE("nativeUpdateCamera: unknown exception");
        }

        env->ReleaseFloatArrayElements(viewMatrix, view, JNI_ABORT);
        env->ReleaseFloatArrayElements(projMatrix, proj, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeUpdateLightLevel(JNIEnv* env, jobject thiz, jfloat level) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) gSlamEngine->updateLightLevel(level);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeUpdateAnchorTransform(JNIEnv* env, jobject thiz, jfloatArray transform) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) {
        // updateAnchorTransform memcpy's 16 floats out of this. A short or null array is dropped
        // here — the Kotlin side must not read that as "an anchor was written", which is why the
        // established-anchor signal is raised at establishment rather than by this setter.
        if (!transform || env->GetArrayLength(transform) < 16) return;
        jfloat* mat = env->GetFloatArrayElements(transform, nullptr);
        gSlamEngine->updateAnchorTransform(mat);
        env->ReleaseFloatArrayElements(transform, mat, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeFeedYuvFrame(
        JNIEnv* env, jobject thiz, jobject yBuffer, jobject uBuffer, jobject vBuffer,
        jint width, jint height, jint yStride, jint uvStride, jint uvPixelStride, jlong timestampNs, jint cvRotateCode) {

    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine) return;

    try {

    uint8_t* yData = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    uint8_t* uData = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    uint8_t* vData = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));

    if (!yData || !uData || !vData) return;

    // The U/V planes below are bounded by GetDirectBufferCapacity; the Y plane wasn't, despite the
    // comment a few lines down claiming all copies here are "bounded by the buffer capacities" --
    // yMat.copyTo reads (height-1)*yStride+width bytes from yData, so a short/truncated yBuffer would
    // read past its end. Same guard style as uCap/vCap: skip the check only when the capacity query
    // itself is unavailable (<=0), matching those call sites.
    jlong yCap = env->GetDirectBufferCapacity(yBuffer);
    size_t yNeeded = (size_t)(height - 1) * (size_t)yStride + (size_t)width;
    if (yCap > 0 && (size_t)yCap < yNeeded) {
        LOGE("nativeFeedYuvFrame: Y buffer too small (cap=%lld, need=%zu)", (long long)yCap, yNeeded);
        return;
    }

    cv::Mat yMat(height, width, CV_8UC1, yData, yStride);

    // This runs on the GL render thread, so every allocation here costs frame time directly.
    // An eagerly allocated CV_8UC3 height x width Mat used to be assigned to gLastColorFrame here
    // and then immediately overwritten below by the YUV frame — a full RGB-sized allocation (~6 MB
    // at 1080p) thrown away on every single call. Its size guard could never match either, because
    // gLastColorFrame ends up height*1.5 rows tall, so it re-allocated every frame forever.
    cv::Mat yuv(height + height / 2, width, CV_8UC1);
    yMat.copyTo(yuv(cv::Rect(0, 0, width, height)));

    // frameSnapshot is a cheap header-copy (shares yuv's/converted buffer, not a deep copy) taken
    // under gColorFrameMutex right alongside the write to gLastColorFrame, so the heavy conversion
    // and reloc-frame work below can safely run unlocked -- see gColorFrameMutex's declaration
    // comment for why gEngineMutex's shared_lock (taken above) no longer serializes this against a
    // concurrent nativeFeedColorFrame call.
    cv::Mat frameSnapshot;
    {
        std::lock_guard<std::mutex> colorLock(gColorFrameMutex);
        if (uvPixelStride == 1) {
            // I420 planar (separate U and V planes) → NV21-style interleaved V,U so the reloc decode
            // (COLOR_YUV2RGB_NV21 below) is correct. The old code copied each full height/2-row plane
            // into a height/4-row ROI — a size mismatch that left half the chroma uninitialized and
            // produced wrong colours. Build the VU block explicitly, bounded by the buffer capacities.
            jlong uCap = env->GetDirectBufferCapacity(uBuffer);
            jlong vCap = env->GetDirectBufferCapacity(vBuffer);
            cv::Mat chroma = yuv(cv::Rect(0, height, width, height / 2));
            for (int r = 0; r < height / 2; ++r) {
                uint8_t* dst = chroma.ptr(r);
                size_t rowOff = (size_t)r * uvStride;
                for (int c = 0; c < width / 2; ++c) {
                    size_t idx = rowOff + c;
                    dst[2 * c]     = (vCap <= 0 || (jlong)idx < vCap) ? vData[idx] : 0; // V
                    dst[2 * c + 1] = (uCap <= 0 || (jlong)idx < uCap) ? uData[idx] : 0; // U
                }
            }
            // No conversion on GL thread; pass raw YUV to map thread. Plain assignment, not clone():
            // `yuv` is a local whose buffer nothing else writes, so cv::Mat's refcount hands ownership
            // over for free. The clone() this replaces copied the whole frame a second time, on the GL
            // thread, every call.
            gLastColorFrame = yuv;
        } else if (uvPixelStride == 2) {
            // Semi-planar (NV12/NV21): the interleaved chroma can be memcpy'd straight into the YUV
            // block's rows. The previous version built a separate zero-filled full-chroma Mat and then
            // copyTo'd it across — an extra allocation, an extra zero-fill and an extra copy per frame.
            jlong vCap = env->GetDirectBufferCapacity(vBuffer);
            cv::Mat chroma = yuv(cv::Rect(0, height, width, height / 2));
            size_t limit = (vCap > 0) ? (size_t)vCap : (size_t)((height / 2 - 1) * uvStride + width);
            for (int r = 0; r < height / 2; ++r) {
                uint8_t* dst = chroma.ptr(r);
                size_t rowStart = (size_t)r * uvStride;
                size_t rowLen = std::min((size_t)width, (size_t)(limit > rowStart ? limit - rowStart : 0));
                if (rowLen > 0) std::memcpy(dst, vData + rowStart, rowLen);
                // Rows the source can't fill must still be cleared: a fresh cv::Mat is uninitialised,
                // whereas the scratch Mat this replaces was zero-filled.
                if (rowLen < (size_t)width) std::memset(dst + rowLen, 0, (size_t)width - rowLen);
            }
            gLastColorFrame = yuv;
        } else {
            cv::cvtColor(yMat, gLastColorFrame, cv::COLOR_GRAY2RGB);
        }
        frameSnapshot = gLastColorFrame;
    }

    // Relocalization MATCHING still uses the Display-Aligned frame for best user feedback.
    //
    // Ask FIRST whether the reloc worker will take a frame. scheduleRelocCheck drops the frame
    // outright when relocalization is off, when there is no wall fingerprint to match against yet,
    // or when the worker is still busy with the previous one — and building its argument costs a
    // full-frame YUV->RGB conversion plus a full-frame rotate, both on the GL render thread. That
    // work used to be done unconditionally, so during the entire scanning phase (no fingerprint
    // exists yet, by definition) every single conversion was built and immediately thrown away,
    // several times a second, stalling the render loop for nothing.
    if (frameSnapshot.empty() || !gSlamEngine->relocWantsFrame()) return;

    if (frameSnapshot.rows == height + height/2) {
        cv::Mat relocFrame;
        cv::cvtColor(frameSnapshot, relocFrame, cv::COLOR_YUV2RGB_NV21);
        if (cvRotateCode >= 0) {
            cv::rotate(relocFrame, relocFrame, cvRotateCode);
        }
        gSlamEngine->scheduleRelocCheck(relocFrame);
    } else {
        cv::Mat relocFrame = frameSnapshot.clone();
        if (cvRotateCode >= 0) {
            cv::rotate(relocFrame, relocFrame, cvRotateCode);
        }
        gSlamEngine->scheduleRelocCheck(relocFrame);
    }

    } catch (const std::exception& e) {
        LOGE("nativeFeedYuvFrame: exception: %s", e.what());
    } catch (...) {
        LOGE("nativeFeedYuvFrame: unknown exception");
    }
}

// YuvConverter.nativeYuvToRgbaBitmap — decode a camera-frame YUV_420_888 into a caller-owned
// ARGB_8888 Bitmap. Reuses the same plane-parsing structure as nativeFeedYuvFrame above (I420 vs
// NV21/NV12 branch keyed on uvPixelStride) but writes into the bitmap instead of into
// gLastColorFrame. Replaces the fake "zero-copy" ImageProcessingUtils path (JPEG round-trip).
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_YuvConverter_nativeYuvToRgbaBitmap(
        JNIEnv* env, jobject thiz,
        jobject yBuffer, jobject uBuffer, jobject vBuffer,
        jint width, jint height,
        jint yStride, jint uvRowStride, jint uvPixelStride,
        jobject outBitmap) {

    uint8_t* yData = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    uint8_t* uData = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    uint8_t* vData = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    if (!yData || !uData || !vData) return;

    // Same Y-plane guard as nativeFeedYuvFrame above: the U/V planes below are bounded by
    // GetDirectBufferCapacity, and the Y plane needs the same check before yMat.copyTo reads
    // (height-1)*yStride+width bytes out of yData.
    jlong yCap = env->GetDirectBufferCapacity(yBuffer);
    size_t yNeeded = (size_t)(height - 1) * (size_t)yStride + (size_t)width;
    if (yCap > 0 && (size_t)yCap < yNeeded) {
        LOGE("nativeYuvToRgbaBitmap: Y buffer too small (cap=%lld, need=%zu)", (long long)yCap, yNeeded);
        return;
    }

    // Build the packed NV21 buffer OpenCV's cvtColor understands. This matches the NV21 layout
    // camera frames from Camera2 / CameraX / ARCore ship in (Y plane, then interleaved V/U).
    cv::Mat yMat(height, width, CV_8UC1, yData, yStride);
    cv::Mat nv21(height + height / 2, width, CV_8UC1);
    yMat.copyTo(nv21(cv::Rect(0, 0, width, height)));

    if (uvPixelStride == 1) {
        // I420 (planar U then V): pack into interleaved VU rows for NV21. Bounded by the buffer
        // capacities, same as nativeFeedYuvFrame's I420 branch above: uMat/vMat wrap uData/vData at
        // uvRowStride without OpenCV knowing the buffer's real length, so a truncated final row (or a
        // caller-supplied stride wider than the actual allocation) would otherwise read past the end.
        jlong uCap = env->GetDirectBufferCapacity(uBuffer);
        jlong vCap = env->GetDirectBufferCapacity(vBuffer);
        cv::Mat vuInterleaved(height / 2, width, CV_8UC1, nv21.ptr(height));
        for (int r = 0; r < height / 2; ++r) {
            uint8_t* row = vuInterleaved.ptr(r);
            size_t rowOff = (size_t)r * uvRowStride;
            for (int c = 0; c < width / 2; ++c) {
                size_t idx = rowOff + c;
                row[2 * c]     = (vCap <= 0 || (jlong)idx < vCap) ? vData[idx] : 0; // V first in NV21
                row[2 * c + 1] = (uCap <= 0 || (jlong)idx < uCap) ? uData[idx] : 0; // then U
            }
        }
    } else if (uvPixelStride == 2) {
        // NV21/NV12 semi-planar: the V plane's buffer already contains the interleaved VU (or UV)
        // rows one byte apart. Copy row-by-row respecting uvRowStride which may be padded past
        // width. Guard on vBuffer capacity in case of a truncated final row.
        jlong vCap = env->GetDirectBufferCapacity(vBuffer);
        size_t limit = (vCap > 0) ? (size_t)vCap : (size_t)((height / 2 - 1) * uvRowStride + width);
        for (int r = 0; r < height / 2; ++r) {
            size_t rowStart = r * uvRowStride;
            size_t rowLen = std::min((size_t)width, (size_t)(limit > rowStart ? limit - rowStart : 0));
            if (rowLen > 0) std::memcpy(nv21.ptr(height + r), vData + rowStart, rowLen);
        }
    } else {
        // Unusual pixelStride (not 1 or 2). No sane camera exposes this; fill grayscale so the
        // caller at least gets a visible frame instead of a crash.
        cv::Mat gray;
        cv::cvtColor(yMat, gray, cv::COLOR_GRAY2RGBA);
        matToBitmap(env, gray, outBitmap);
        return;
    }

    // NEON-optimised on ARM; runs in a couple ms for 1080p.
    cv::Mat rgba;
    cv::cvtColor(nv21, rgba, cv::COLOR_YUV2RGBA_NV21);

    // matToBitmap locks the pixels, copies with cvtColor as needed, then unlocks. rgba is already
    // CV_8UC4, so this becomes a direct memcpy of `width*height*4` bytes.
    matToBitmap(env, rgba, outBitmap);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeFeedColorFrame(
        JNIEnv* env, jobject thiz, jobject colorBuffer, jint width, jint height, jlong timestampNs, jint cvRotateCode) {

    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    uint8_t* buffer = static_cast<uint8_t*>(env->GetDirectBufferAddress(colorBuffer));
    if (!buffer || !gSlamEngine || width <= 0 || height <= 0) return;

    // Same guard as nativeFeedYuvFrame's Y-plane check: wrapping the caller's buffer in a cv::Mat
    // with no bounds check lets a short/truncated colorBuffer (wrong stride assumption on the
    // caller's side, e.g. a glasses-forwarded frame) read past its end. Skip the check only when
    // the capacity query itself is unavailable, matching that call site's style.
    jlong colorCap = env->GetDirectBufferCapacity(colorBuffer);
    size_t colorNeeded = (size_t)height * (size_t)width * 4;
    if (colorCap > 0 && (size_t)colorCap < colorNeeded) {
        LOGE("nativeFeedColorFrame: buffer too small (cap=%lld, need=%zu)", (long long)colorCap, colorNeeded);
        return;
    }

    try {
        cv::Mat frame(height, width, CV_8UC4, buffer);
        cv::Mat relocFrame;
        {
            // See gColorFrameMutex's declaration comment: this and nativeFeedYuvFrame both only
            // hold gEngineMutex's shared_lock now, so they need their own mutex around the
            // read-modify-write of gLastColorFrame. The clone() (the actual per-frame cost) stays
            // inside the lock here -- unlike nativeFeedYuvFrame's snapshot-then-unlock, this
            // conversion is cheap enough (a single RGBA->RGB cvtColor + clone, not a multi-branch
            // YUV assembly) that splitting it wouldn't measurably reduce contention, and keeping
            // it simple avoids a second place to get the snapshot pattern wrong.
            std::lock_guard<std::mutex> colorLock(gColorFrameMutex);
            cv::cvtColor(frame, gLastColorFrame, cv::COLOR_RGBA2RGB);
            relocFrame = gLastColorFrame.clone();
        }
        if (cvRotateCode >= 0) {
            cv::rotate(relocFrame, relocFrame, cvRotateCode);
        }
        // In EVAL SYNC MODE, scheduleRelocCheck runs the reloc pass (solvePnPRansac and friends)
        // inline on this thread rather than handing off to the background worker -- same exception
        // hazard nativeFeedYuvFrame guards against.
        gSlamEngine->scheduleRelocCheck(relocFrame);
    } catch (const std::exception& e) {
        LOGE("nativeFeedColorFrame: exception: %s", e.what());
    } catch (...) {
        LOGE("nativeFeedColorFrame: unknown exception");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeLoadSuperPoint(
        JNIEnv* env, jobject thiz, jobject assetManager) {
    std::unique_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine) return JNI_FALSE;
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    AAsset* asset = AAssetManager_open(mgr, "superpoint.onnx", AASSET_MODE_BUFFER);
    if (!asset) return JNI_FALSE;
    size_t size = (size_t)AAsset_getLength(asset);
    std::vector<uchar> buf(size);
    AAsset_read(asset, buf.data(), (off_t)size);
    AAsset_close(asset);
    bool ok = gSlamEngine->loadSuperPoint(buf);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeLoadDistortionHead(
        JNIEnv* env, jobject thiz, jobject assetManager) {
    std::unique_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine) return JNI_FALSE;
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    AAsset* asset = AAssetManager_open(mgr, "distortion_head.onnx", AASSET_MODE_BUFFER);
    if (!asset) {
        __android_log_print(ANDROID_LOG_WARN, "GraffitiJNI", "distortion_head.onnx not in assets — head disabled");
        return JNI_FALSE; // optional model: absent => head stays inert
    }
    size_t size = (size_t)AAsset_getLength(asset);
    std::vector<uchar> buf(size);
    AAsset_read(asset, buf.data(), (off_t)size);
    AAsset_close(asset);
    bool ok = gSlamEngine->loadDistortionHead(buf);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeLoadLowLightEnhancer(
        JNIEnv* env, jobject thiz, jobject assetManager) {
    std::unique_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine) return;
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    AAsset* asset = AAssetManager_open(mgr, "zerodce.onnx", AASSET_MODE_BUFFER);
    if (!asset) {
        __android_log_print(ANDROID_LOG_WARN, "GraffitiJNI", "zerodce.onnx not found in assets");
        return;
    }
    size_t size = (size_t)AAsset_getLength(asset);
    std::vector<uchar> buf(size);
    AAsset_read(asset, buf.data(), (off_t)size);
    AAsset_close(asset);
    gSlamEngine->loadLowLightEnhancer(buf);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeRestoreWallFingerprint(
        JNIEnv* env, jobject thiz, jbyteArray descArray, jint rows, jint cols, jint type, jfloatArray ptsArray) {
    // Defensive validation (a malformed/old .gxr must never crash native): non-null refs, a valid
    // OpenCV type (a bogus one makes the cv::Mat ctor throw a cv::Exception → hard process crash,
    // since JNI can't catch C++ exceptions), and a descriptor blob at least rows*cols*elemSize.
    // The size product is computed in 64-bit so a hostile rows*cols can't overflow past the check.
    // Mirrors the guarded nativeRestoreWallFeatureMap path; the metric sibling below does the same.
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine || !descArray || !ptsArray) return;
    if (rows < 0 || cols < 0) return;
    int depth = CV_MAT_DEPTH(type);
    int channels = CV_MAT_CN(type);
    if (depth < 0 || depth > CV_64F || channels < 1 || channels > 4) return;
    if ((jlong)rows * (jlong)cols * (jlong)CV_ELEM_SIZE(type) > (jlong)env->GetArrayLength(descArray)) return;

    jbyte* descData = env->GetByteArrayElements(descArray, nullptr);
    cv::Mat descriptors(rows, cols, type, descData);
    jsize ptsLen = env->GetArrayLength(ptsArray);
    jfloat* ptsData = env->GetFloatArrayElements(ptsArray, nullptr);
    std::vector<cv::Point3f> points3d;
    for (int i = 0; i + 2 < ptsLen; i += 3) {
        points3d.push_back(cv::Point3f(ptsData[i], ptsData[i+1], ptsData[i+2]));
    }
    try {
        gSlamEngine->restoreWallFingerprint(descriptors, points3d);
    } catch (const std::exception& e) {
        LOGE("nativeRestoreWallFingerprint: exception: %s", e.what());
    } catch (...) {
        LOGE("nativeRestoreWallFingerprint: unknown exception");
    }
    env->ReleaseByteArrayElements(descArray, descData, JNI_ABORT);
    env->ReleaseFloatArrayElements(ptsArray, ptsData, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeRestoreWallFingerprintMetric(
        JNIEnv* env, jobject thiz, jbyteArray descArray, jint rows, jint cols, jint type,
        jfloatArray ptsArray, jfloatArray anchorArray, jfloatArray intrArray, jfloatArray viewArray,
        jbyteArray regionsArray) {
    // Same defensive validation as the plain restore: reject a malformed/old .gxr before cv::Mat
    // wraps the descriptor blob (valid OpenCV type + 64-bit overflow-safe size check), and only pass
    // anchor/intrinsics when correctly sized (native copies a fixed 16 / 4 floats and tolerates null),
    // else leave the native defaults.
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine || !descArray || !ptsArray) return;
    if (rows < 0 || cols < 0) return;
    int depth = CV_MAT_DEPTH(type);
    int channels = CV_MAT_CN(type);
    if (depth < 0 || depth > CV_64F || channels < 1 || channels > 4) return;
    if ((jlong)rows * (jlong)cols * (jlong)CV_ELEM_SIZE(type) > (jlong)env->GetArrayLength(descArray)) return;
    jbyte* descData = env->GetByteArrayElements(descArray, nullptr);
    cv::Mat descriptors(rows, cols, type, descData);
    jsize ptsLen = env->GetArrayLength(ptsArray);
    jfloat* ptsData = env->GetFloatArrayElements(ptsArray, nullptr);
    std::vector<cv::Point3f> points3d;
    points3d.reserve(ptsLen / 3);
    for (int i = 0; i + 2 < ptsLen; i += 3) {
        points3d.push_back(cv::Point3f(ptsData[i], ptsData[i+1], ptsData[i+2]));
    }
    jfloat* anchor = (anchorArray && env->GetArrayLength(anchorArray) == 16) ? env->GetFloatArrayElements(anchorArray, nullptr) : nullptr;
    jfloat* intr   = (intrArray && env->GetArrayLength(intrArray) == 4)    ? env->GetFloatArrayElements(intrArray, nullptr)   : nullptr;
    jfloat* view   = (viewArray && env->GetArrayLength(viewArray) == 16)   ? env->GetFloatArrayElements(viewArray, nullptr)   : nullptr;
    // Phase 2 partition: one byte per 3D point. An empty array is the legacy "all backbone" case and
    // is passed through as empty. A NON-empty array whose length disagrees with the point count is
    // dropped rather than truncated: native indexes it by point when building reloc correspondences,
    // so a short array reads past the end of a vector, and a long one means the two disagree about
    // what the map even is. Dropping degrades to all-backbone, which is safe; truncating is not.
    std::vector<uint8_t> regions;
    if (regionsArray) {
        jsize regLen = env->GetArrayLength(regionsArray);
        if (regLen > 0 && (size_t)regLen == points3d.size()) {
            jbyte* regData = env->GetByteArrayElements(regionsArray, nullptr);
            regions.assign((uint8_t*)regData, (uint8_t*)regData + regLen);
            env->ReleaseByteArrayElements(regionsArray, regData, JNI_ABORT);
        } else if (regLen > 0) {
            LOGE("restoreWallFingerprintMetric: %d regions for %zu points - dropping partition",
                 (int)regLen, points3d.size());
        }
    }
    try {
        gSlamEngine->restoreWallFingerprintMetric(descriptors, points3d, anchor, intr, view, regions);
    } catch (const std::exception& e) {
        LOGE("nativeRestoreWallFingerprintMetric: exception: %s", e.what());
    } catch (...) {
        LOGE("nativeRestoreWallFingerprintMetric: unknown exception");
    }
    env->ReleaseByteArrayElements(descArray, descData, JNI_ABORT);
    env->ReleaseFloatArrayElements(ptsArray, ptsData, JNI_ABORT);
    if (anchor) env->ReleaseFloatArrayElements(anchorArray, anchor, JNI_ABORT);
    if (intr)   env->ReleaseFloatArrayElements(intrArray, intr, JNI_ABORT);
    if (view)   env->ReleaseFloatArrayElements(viewArray, view, JNI_ABORT);
}

// Persistent wall feature map (Phase 2a: store only). Mirrors the metric-fingerprint restore but
// adds parallel per-point confidence (jfloatArray) + obs (jintArray); anchor/intrinsics are
// passed through only when correctly sized (16 / 4), else left at their native defaults.
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeRestoreWallFeatureMap(
        JNIEnv* env, jobject thiz, jbyteArray descArray, jint rows, jint cols, jint type,
        jfloatArray ptsArray, jfloatArray confArray, jintArray obsArray,
        jfloatArray anchorArray, jfloatArray intrArray) {
    // Defensive validation (a malformed/old .gxr must never crash native): null refs, a valid OpenCV
    // type (a bogus one makes the cv::Mat ctor throw a cv::Exception -> hard process crash, since JNI
    // can't catch C++ exceptions), a descriptor blob big enough for rows*cols*elemSize computed in
    // 64-bit (a jsize/jint product here would let a hostile rows*cols overflow past the check and
    // wrap a tiny buffer in a huge cv::Mat -> OOB read), and parallel arrays of matching length.
    // Mirrors nativeRestoreWallFingerprint / nativeRestoreWallFingerprintMetric above.
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine || !descArray || !ptsArray) return;
    if (rows < 0 || cols < 0) return;
    int depth = CV_MAT_DEPTH(type);
    int channels = CV_MAT_CN(type);
    if (depth < 0 || depth > CV_64F || channels < 1 || channels > 4) return;
    jsize descLen = env->GetArrayLength(descArray);
    if ((jlong)rows * (jlong)cols * (jlong)CV_ELEM_SIZE(type) > (jlong)descLen) return;
    jsize ptsLen = env->GetArrayLength(ptsArray);
    // 64-bit, matching the rows*cols*elemSize check above: `rows * 3` computed in 32-bit jint can
    // wrap for a hostile large `rows`, making this comparison pass against the wrapped value even
    // though the real row count is huge — which would then feed `points3d.reserve(rows)` below,
    // outside any try/catch, and a multi-gigabyte reserve() throws std::bad_alloc -> std::terminate.
    if ((jlong)ptsLen != (jlong)rows * 3LL) return;
    jsize confLen = confArray ? env->GetArrayLength(confArray) : 0;
    if (confLen > 0 && confLen != rows) return;
    jsize obsLen = obsArray ? env->GetArrayLength(obsArray) : 0;
    if (obsLen > 0 && obsLen != rows) return;

    jbyte* descData = env->GetByteArrayElements(descArray, nullptr);
    cv::Mat descriptors(rows, cols, type, descData);
    jfloat* ptsData = env->GetFloatArrayElements(ptsArray, nullptr);
    std::vector<cv::Point3f> points3d;
    points3d.reserve(rows);
    for (int i = 0; i + 2 < ptsLen; i += 3)
        points3d.push_back(cv::Point3f(ptsData[i], ptsData[i+1], ptsData[i+2]));

    std::vector<float> conf;
    if (confLen > 0) {
        jfloat* c = env->GetFloatArrayElements(confArray, nullptr);
        conf.assign(c, c + confLen);
        env->ReleaseFloatArrayElements(confArray, c, JNI_ABORT);
    }
    std::vector<int> obs;
    if (obsLen > 0) {
        jint* o = env->GetIntArrayElements(obsArray, nullptr);
        obs.assign(o, o + obsLen);
        env->ReleaseIntArrayElements(obsArray, o, JNI_ABORT);
    }

    jfloat* anchor = (anchorArray && env->GetArrayLength(anchorArray) == 16) ? env->GetFloatArrayElements(anchorArray, nullptr) : nullptr;
    jfloat* intr   = (intrArray && env->GetArrayLength(intrArray) == 4)    ? env->GetFloatArrayElements(intrArray, nullptr)   : nullptr;

    try {
        gSlamEngine->restoreWallFeatureMap(descriptors, points3d, conf, obs, anchor, intr);
    } catch (const std::exception& e) {
        LOGE("nativeRestoreWallFeatureMap: exception: %s", e.what());
    } catch (...) {
        LOGE("nativeRestoreWallFeatureMap: unknown exception");
    }

    env->ReleaseByteArrayElements(descArray, descData, JNI_ABORT);
    env->ReleaseFloatArrayElements(ptsArray, ptsData, JNI_ABORT);
    if (anchor) env->ReleaseFloatArrayElements(anchorArray, anchor, JNI_ABORT);
    if (intr)   env->ReleaseFloatArrayElements(intrArray, intr, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeClearWallFeatureMap(JNIEnv*, jobject) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) gSlamEngine->clearWallFeatureMap();
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeClearWallFingerprint(JNIEnv*, jobject) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) gSlamEngine->clearWallFingerprint();
}

JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetMapPointCount(JNIEnv*, jobject) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    return gSlamEngine ? gSlamEngine->getMapPointCount() : 0;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetMapRelocEnabled(JNIEnv*, jobject, jboolean enabled) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) gSlamEngine->setMapRelocEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetMapBuildEnabled(JNIEnv*, jobject, jboolean enabled) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) gSlamEngine->setMapBuildEnabled(enabled == JNI_TRUE);
}

JNIEXPORT jbyteArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeExportWallFeatureMap(JNIEnv* env, jobject) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine) return nullptr;
    std::vector<uint8_t> blob = gSlamEngine->exportWallFeatureMap();
    if (blob.empty()) return nullptr;
    jbyteArray arr = env->NewByteArray((jsize)blob.size());
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, (jsize)blob.size(), reinterpret_cast<const jbyte*>(blob.data()));
    return arr;
}

jobject buildFingerprintObject(JNIEnv* env, const MobileGS::FingerprintData& fd) {
    if (fd.descriptors.empty()) return nullptr;

    // Helper: clear any pending JNI exception and bail to nullptr on lookup
    // failure. Without this, GetMethodID returning null and then NewObject
    // being called with that null mid aborts the process with
    // "JNI DETECTED ERROR IN APPLICATION: mid == null". That can happen
    // when R8 strips a constructor it doesn't see called from Kotlin/Java.
    auto bail = [&](const char* what) -> jobject {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        __android_log_print(ANDROID_LOG_ERROR, "GraffitiJNI",
            "buildFingerprintObject: lookup failed: %s — returning nullptr", what);
        return nullptr;
    };

    jclass listClass = env->FindClass("java/util/ArrayList");
    if (!listClass) return bail("ArrayList class");
    jmethodID listCtor = env->GetMethodID(listClass, "<init>", "(I)V");
    if (!listCtor) return bail("ArrayList(int) ctor");
    jmethodID addMethod = env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");
    if (!addMethod) return bail("ArrayList.add");

    // Keypoints: List<org.opencv.core.KeyPoint>
    jclass kpClass = env->FindClass("org/opencv/core/KeyPoint");
    if (!kpClass) return bail("KeyPoint class");
    jmethodID kpCtor = env->GetMethodID(kpClass, "<init>", "(FFFFFII)V");
    if (!kpCtor) return bail("KeyPoint ctor");
    jobject kpList = env->NewObject(listClass, listCtor, (jint)fd.keypoints.size());
    if (!kpList) return bail("keypoint list alloc");
    for (const auto& kp : fd.keypoints) {
        jobject jkp = env->NewObject(kpClass, kpCtor, kp.pt.x, kp.pt.y, kp.size, kp.angle, kp.response, (jint)kp.octave, (jint)kp.class_id);
        if (!jkp) return bail("KeyPoint alloc");
        env->CallBooleanMethod(kpList, addMethod, jkp);
        env->DeleteLocalRef(jkp);
    }

    // Points3D: List<Float>
    jclass floatClass = env->FindClass("java/lang/Float");
    if (!floatClass) return bail("Float class");
    jmethodID floatCtor = env->GetMethodID(floatClass, "<init>", "(F)V");
    if (!floatCtor) return bail("Float(f) ctor");
    jobject ptsList = env->NewObject(listClass, listCtor, (jint)fd.points3d.size());
    if (!ptsList) return bail("points3d list alloc");
    for (float f : fd.points3d) {
        jobject jf = env->NewObject(floatClass, floatCtor, f);
        if (!jf) return bail("Float alloc");
        env->CallBooleanMethod(ptsList, addMethod, jf);
        env->DeleteLocalRef(jf);
    }

    // DescriptorsData: byte[]
    jsize descSize = fd.descriptors.total() * fd.descriptors.elemSize();
    jbyteArray descArray = env->NewByteArray(descSize);
    if (!descArray) return bail("descriptor array alloc");
    env->SetByteArrayRegion(descArray, 0, descSize, (const jbyte*)fd.descriptors.data);
    if (env->ExceptionCheck()) return bail("descriptor array copy");

    jclass fpClass = env->FindClass("com/hereliesaz/graffitixr/common/model/Fingerprint");
    if (!fpClass) return bail("Fingerprint class");
    // FROZEN JNI ABI: construct via the static factory Fingerprint.fromNative, never the raw
    // constructor. Kotlin default parameters don't emit reduced-arity JVM overloads, so every
    // field added to the data class changed the constructor descriptor and broke this lookup
    // (twice: patchData, then markCenterLocal), making setWallFingerprint return null on every
    // capture. The factory signature is frozen — mirrored by Fingerprint.JNI_FACTORY_DESCRIPTOR
    // and asserted by FingerprintJniContractTest — so new Kotlin fields can't break it again.
    jmethodID fpFactory = env->GetStaticMethodID(
            fpClass, "fromNative",
            "(Ljava/util/List;Ljava/util/List;[BIII[BLjava/util/List;)Lcom/hereliesaz/graffitixr/common/model/Fingerprint;");
    if (!fpFactory) return bail("Fingerprint.fromNative factory");

    // patchData (the distortion-head patch): this native path produces none — FingerprintData has
    // no patch field — so pass an empty array, matching the Kotlin default `patchData = ByteArray(0)`.
    jbyteArray patchArray = env->NewByteArray(0);
    if (!patchArray) return bail("patchArray alloc");
    // markCenterLocal: FingerprintData carries no marks centroid (it is computed Kotlin-side by
    // MetricFingerprintBuilder), so pass an empty list, matching the Kotlin default.
    jobject centerList = env->NewObject(listClass, listCtor, (jint)0);
    if (!centerList) return bail("markCenterLocal list alloc");
    jobject fpObj = env->CallStaticObjectMethod(fpClass, fpFactory, kpList, ptsList, descArray,
                                                fd.descriptors.rows, fd.descriptors.cols, fd.descriptors.type(),
                                                patchArray, centerList);
    if (env->ExceptionCheck() || !fpObj) return bail("Fingerprint.fromNative call");

    return fpObj;
}

JNIEXPORT jobject JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetWallFingerprint(
        JNIEnv* env, jobject thiz, jobject bitmap, jobject mask, jobject depthBuffer, jint depthW, jint depthH, jint depthStride, jfloatArray intrArray, jfloatArray viewMatArray) {

    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine) return nullptr;
    // generateFingerprint dereferences intr[0..3] and viewMat[0..15] unconditionally (unlike the
    // optional-pointer restore paths above), so a null or short array here is an OOB read past
    // whatever GetFloatArrayElements happens to hand back. Not reachable via current Kotlin call
    // sites, but every other array-taking function in this file validates before use.
    if (!intrArray || env->GetArrayLength(intrArray) < 4) return nullptr;
    if (!viewMatArray || env->GetArrayLength(viewMatArray) < 16) return nullptr;

    cv::Mat image;
    bitmapToMat(env, bitmap, image);

    cv::Mat maskMat;
    if (mask) bitmapToMat(env, mask, maskMat);

    auto* depthData = static_cast<const uint8_t*>(env->GetDirectBufferAddress(depthBuffer));
    jfloat* intr = env->GetFloatArrayElements(intrArray, nullptr);
    jfloat* view = env->GetFloatArrayElements(viewMatArray, nullptr);

    MobileGS::FingerprintData fd;
    try {
        fd = gSlamEngine->generateFingerprint(image, maskMat, depthData, depthW, depthH, depthStride, intr, view);
    } catch (const std::exception& e) {
        LOGE("nativeSetWallFingerprint: exception: %s", e.what());
    } catch (...) {
        LOGE("nativeSetWallFingerprint: unknown exception");
    }

    env->ReleaseFloatArrayElements(intrArray, intr, JNI_ABORT);
    env->ReleaseFloatArrayElements(viewMatArray, view, JNI_ABORT);

    return buildFingerprintObject(env, fd);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetArtworkFingerprint(
        JNIEnv* env, jobject thiz, jobject bitmap, jobject depthBuffer, jint depthW, jint depthH, jint depthStride, jfloatArray intrArray, jfloatArray viewMatArray) {
    // Same OOB hazard as nativeSetWallFingerprint above: setArtworkFingerprint dereferences
    // intr[0..3] / viewMat[0..15] unconditionally. Validate before touching either array.
    if (!intrArray || env->GetArrayLength(intrArray) < 4) return;
    if (!viewMatArray || env->GetArrayLength(viewMatArray) < 16) return;
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) {
        cv::Mat composite;
        bitmapToMat(env, bitmap, composite);
        auto* depthData = static_cast<const uint8_t*>(env->GetDirectBufferAddress(depthBuffer));
        jfloat* intr = env->GetFloatArrayElements(intrArray, nullptr);
        jfloat* view = env->GetFloatArrayElements(viewMatArray, nullptr);
        try {
            gSlamEngine->setArtworkFingerprint(composite, depthData, depthW, depthH, depthStride, intr, view);
        } catch (const std::exception& e) {
            LOGE("nativeSetArtworkFingerprint: exception: %s", e.what());
        } catch (...) {
            LOGE("nativeSetArtworkFingerprint: unknown exception");
        }
        env->ReleaseFloatArrayElements(intrArray, intr, JNI_ABORT);
        env->ReleaseFloatArrayElements(viewMatArray, view, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeAnnotateKeypoints(
        JNIEnv* env, jobject thiz, jobject bitmap) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine) return;
    cv::Mat frame;
    bitmapToMat(env, bitmap, frame);
    if (frame.empty()) return;

    cv::Mat gray;
    if (frame.channels() == 4) cv::cvtColor(frame, gray, cv::COLOR_RGBA2GRAY);
    else if (frame.channels() == 3) cv::cvtColor(frame, gray, cv::COLOR_RGB2GRAY);
    else gray = frame.clone();

    std::vector<cv::KeyPoint> kps;
    cv::Mat descs;
    if (gSlamEngine) {
        // Scoped lock so an exception from detectAndCompute can't leak the mutex
        // and permanently deadlock every other JNI entry point.
        std::lock_guard<std::mutex> lock(gSlamEngine->getMutex());
        try {
            // Use consistent feature detection for visualization
            cv::ORB::create(500)->detectAndCompute(gray, cv::noArray(), kps, descs);
        } catch (const std::exception& e) {
            LOGE("nativeAnnotateKeypoints: exception: %s", e.what());
        } catch (...) {
            LOGE("nativeAnnotateKeypoints: unknown exception");
        }
    }

    // Convert frame to RGBA if it isn't already for drawing
    cv::Mat annotated;
    if (frame.channels() == 4) annotated = frame.clone();
    else if (frame.channels() == 3) cv::cvtColor(frame, annotated, cv::COLOR_RGB2RGBA);
    else cv::cvtColor(frame, annotated, cv::COLOR_GRAY2RGBA);

    cv::drawKeypoints(annotated, kps, annotated, cv::Scalar(0, 255, 0, 255), cv::DrawMatchesFlags::DRAW_RICH_KEYPOINTS);

    matToBitmap(env, annotated, bitmap);
}

JNIEXPORT jfloatArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeDetectSuperPoint(
        JNIEnv* env, jobject thiz, jobject bitmap) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine) return nullptr;
    cv::Mat image; bitmapToMat(env, bitmap, image);
    if (image.empty()) return nullptr;
    std::vector<cv::KeyPoint> kps; cv::Mat descs;
    {
        std::lock_guard<std::mutex> lock(gSlamEngine->getMutex());
        bool ok = false;
        try {
            ok = gSlamEngine->getSuperPointFeatures(image, kps, descs);
        } catch (const std::exception& e) {
            LOGE("nativeDetectSuperPoint: exception: %s", e.what());
        } catch (...) {
            LOGE("nativeDetectSuperPoint: unknown exception");
        }
        if (!ok) return nullptr;
    }
    const int n = (int)kps.size();
    const int d = descs.cols;
    if (n <= 0 || d <= 0) return nullptr;
    cv::Mat dc = descs.isContinuous() ? descs : descs.clone();
    // Packed layout: [n, d, (u,v) * n, descriptors row-major (n*d)].
    jfloatArray result = env->NewFloatArray(2 + 2 * n + n * d);
    if (!result) return nullptr;
    jfloat* ptr = env->GetFloatArrayElements(result, nullptr);
    if (!ptr) return nullptr;
    ptr[0] = (float)n; ptr[1] = (float)d;
    for (int i = 0; i < n; ++i) { ptr[2 + 2*i] = kps[i].pt.x; ptr[2 + 2*i + 1] = kps[i].pt.y; }
    std::memcpy(ptr + 2 + 2*n, dc.ptr<float>(0), (size_t)n * d * sizeof(float));
    env->ReleaseFloatArrayElements(result, ptr, 0);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetFingerprintKeypoints(
        JNIEnv* env, jobject thiz, jobject bitmap, jobject mask) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine) return nullptr;
    cv::Mat image; bitmapToMat(env, bitmap, image);
    if (image.empty()) return nullptr;
    cv::Mat maskMat;
    if (mask) bitmapToMat(env, mask, maskMat);
    std::vector<cv::Point2f> pts;
    {
        std::lock_guard<std::mutex> lock(gSlamEngine->getMutex());
        try {
            gSlamEngine->getFingerprintKeypoints(image, maskMat, pts);
        } catch (const std::exception& e) {
            LOGE("nativeGetFingerprintKeypoints: exception: %s", e.what());
        } catch (...) {
            LOGE("nativeGetFingerprintKeypoints: unknown exception");
        }
    }
    jfloatArray result = env->NewFloatArray((jsize)(pts.size() * 2));
    if (!result) return nullptr;
    jfloat* ptr = env->GetFloatArrayElements(result, nullptr);
    if (!ptr) return nullptr;
    for (size_t i = 0; i < pts.size(); ++i) { ptr[i * 2] = pts[i].x; ptr[i * 2 + 1] = pts[i].y; }
    env->ReleaseFloatArrayElements(result, ptr, 0);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetKeypoints(
        JNIEnv* env, jobject thiz, jobject bitmap) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine) return nullptr;
    cv::Mat frame;
    bitmapToMat(env, bitmap, frame);
    if (frame.empty()) return nullptr;

    cv::Mat gray;
    if (frame.channels() == 4) cv::cvtColor(frame, gray, cv::COLOR_RGBA2GRAY);
    else if (frame.channels() == 3) cv::cvtColor(frame, gray, cv::COLOR_RGB2GRAY);
    else gray = frame.clone();

    std::vector<cv::KeyPoint> kps;
    if (gSlamEngine) {
        std::lock_guard<std::mutex> lock(gSlamEngine->getMutex());
        try {
            cv::ORB::create(500)->detect(gray, kps);
        } catch (const std::exception& e) {
            LOGE("nativeGetKeypoints: exception: %s", e.what());
        } catch (...) {
            LOGE("nativeGetKeypoints: unknown exception");
        }
    }

    jfloatArray result = env->NewFloatArray((jsize)(kps.size() * 2));
    if (!result) return nullptr;
    jfloat* ptr = env->GetFloatArrayElements(result, nullptr);
    if (!ptr) return nullptr;
    for (size_t i = 0; i < kps.size(); ++i) {
        ptr[i * 2] = kps[i].pt.x;
        ptr[i * 2 + 1] = kps[i].pt.y;
    }
    env->ReleaseFloatArrayElements(result, ptr, 0);

    return result;
}


extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetAnchorTransform(JNIEnv* env, jobject) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    // Every Kotlin call site (ArRenderer.kt, ArViewModel.kt) treats a null return as "no engine /
    // allocation pressure" and falls back accordingly -- returning a non-null all-zero array here
    // instead silently collapsed the caller's model matrix / fed a garbage pose into
    // MetricFingerprintBuilder. Match nativeGetFingerprintAnchor's sibling behavior: only allocate
    // and populate the array once there's an engine to read from.
    if (!gSlamEngine) return nullptr;
    jfloatArray result = env->NewFloatArray(16);
    if (!result) return nullptr;
    float mat[16];
    gSlamEngine->getAnchorTransform(mat);
    env->SetFloatArrayRegion(result, 0, 16, mat);
    return result;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetPaintingProgress(JNIEnv* env, jobject) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) return gSlamEngine->getPaintingProgress();
    return 0.0f;
}

// Corroboration confidence: how much the wall backs the design RIGHT NOW, as opposed to how much of
// it has been painted. Negative means no attempt has produced a measurement yet — with no engine
// there is certainly no measurement, so the fallback is the sentinel and not a confident zero.
extern "C" JNIEXPORT jfloat JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetCorroborationConfidence(JNIEnv* env, jobject) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) return gSlamEngine->getCorroborationConfidence();
    return -1.0f;
}

// Relocalization diagnostics: why the last attempt did not publish, and the counts it reached.
// Packed into one int[] so the UI reads a consistent snapshot instead of nine racing getters.
// [0] = RelocReject code, [1] = correspondences, [2] = RANSAC inliers, [3] = features detected,
// [4] = obliquity in degrees (-1 when the rectification pass wasn't eligible), [5] = correspondences
// the rectification pass contributed, [6] = stored points in F_out, [7] = correspondences built
// against F_out, [8] = inliers that came from F_out (IMPLEMENTATION.md 2.11; -1 = not measured),
// [9] = design features the current pose predicts are visible, [10] = how many of those the wall
// answered for, [11] = how many predicted features the gated match refused to test because their
// neighbourhood held a single candidate (IMPLEMENTATION.md 4.6; all three -1 until a GATED
// corroboration attempt has run, and 0 for [9] is a real reading meaning the artist is not looking
// at the design), [12] = CorrobGate ordinal (why the gated match did or did not run),
// [13] = GrowOutcome ordinal (what self-grow did, or which gate declined). The last two are
// REASONS rather than counts: every one of their values produces the same -1s above, and the
// responses they call for are completely different.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetRelocDiagnostics(JNIEnv* env, jobject) {
    // NOT {0, ...}: zero is kRelocOk, so a no-engine fallback of 0 reports a SUCCESSFUL LOCK with
    // zero correspondences — and the reject histogram this feeds would then count "the engine was
    // not initialized" as a win. That is the same defect MobileGS.h guards against in
    // mLastRelocReject's initializer, reintroduced here two files away. 7 is RelocReject.UNKNOWN,
    // the Kotlin-only code that absorbs anything the native side cannot account for; the counts are
    // -1 (not sampled) rather than 0, because zero matches is a real measurement.
    static constexpr jint kRelocUnknownOrdinal = 7;
    // [12] and [13] default to the "not run" ordinals (kCorrobNotRun = 6, kGrowNotRun = 0) rather
    // than -1: they are enums, and with no engine nothing ran, which is exactly what those say.
    jint vals[14] = {kRelocUnknownOrdinal, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 6, 0};
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) {
        vals[0] = gSlamEngine->lastRelocReject();
        vals[1] = gSlamEngine->lastRelocMatches();
        vals[2] = gSlamEngine->lastRelocInliers();
        vals[3] = gSlamEngine->lastRelocDetected();
        vals[4] = gSlamEngine->lastRelocObliquityDeg();
        vals[5] = gSlamEngine->lastRelocRectifiedCorr();
        vals[6] = gSlamEngine->lastRelocBackboneFeatures();
        vals[7] = gSlamEngine->lastRelocBackboneMatches();
        vals[8] = gSlamEngine->lastRelocBackboneInliers();
        vals[9] = gSlamEngine->corrobPredicted();
        vals[10] = gSlamEngine->corrobMatched();
        vals[11] = gSlamEngine->corrobLoneSkips();
        vals[12] = gSlamEngine->corrobGateReason();
        vals[13] = gSlamEngine->growOutcome();
    }
    jintArray out = env->NewIntArray(14);
    if (!out) return nullptr;
    env->SetIntArrayRegion(out, 0, 14, vals);
    return out;
}

// IMPLEMENTATION.md 4.6 — the two float diagnostics from the corroboration path, which cannot ride
// the int[] above. Packed together for the same reason it is packed: one call, one snapshot.
// [0] = search radius the last gated attempt used, in pixels; [1] = mean reprojection error over the
// last lock's PnP inliers, also in pixels; [2] = the last lock's inlier bounding box as a fraction
// of frame area (IMPLEMENTATION.md 3.3). All -1 for "not measured", never 0 — a perfectly tracking
// pose really does have a near-zero reprojection error, and a spread of 0 is a real reading meaning
// every inlier landed on one pixel, which is the worst-conditioned state the gate exists to reject.
// Neither can double as the default.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetCorroborationDiagnostics(JNIEnv* env, jobject) {
    jfloat vals[3] = {-1.0f, -1.0f, -1.0f};
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) {
        vals[0] = gSlamEngine->corrobSearchRadiusPx();
        vals[1] = gSlamEngine->lastRelocReprojPx();
        vals[2] = gSlamEngine->lastRelocInlierSpread();
    }
    jfloatArray out = env->NewFloatArray(3);
    if (!out) return nullptr;
    env->SetFloatArrayRegion(out, 0, 3, vals);
    return out;
}

// IMPLEMENTATION.md 4.5 — where the design sits, so the corroboration match can predict where each
// of its features should appear. A null or wrong-length matrix clears the placement rather than
// being padded: the corroboration match then falls back to its global search, which is Phase 4 off
// and not Phase 4 guessing.
extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetDesignPlacement(
        JNIEnv* env, jobject, jfloatArray fpFromDesign16, jfloat halfW, jfloat halfH) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine) return;
    if (!fpFromDesign16 || env->GetArrayLength(fpFromDesign16) != 16) {
        gSlamEngine->setDesignPlacement(nullptr, 0.0f, 0.0f);
        return;
    }
    jfloat* m = env->GetFloatArrayElements(fpFromDesign16, nullptr);
    gSlamEngine->setDesignPlacement(m, halfW, halfH);
    env->ReleaseFloatArrayElements(fpFromDesign16, m, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetStageTimings(JNIEnv* env, jobject, jfloatArray out) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine) return;
    // SetFloatArrayRegion bounds-checks and throws on a short array, so this is not a memory-safety
    // hole either way -- but every other array-taking function in this file validates length before
    // writing, and a short `out` here should behave the same way (a clear log line) rather than an
    // opaque ArrayIndexOutOfBoundsException surfacing from inside SetFloatArrayRegion.
    if (!out || env->GetArrayLength(out) < 5) {
        LOGE("nativeGetStageTimings: out array too short (need 5)");
        return;
    }
    float buf[5] = {0,0,0,0,0};
    gSlamEngine->getStageTimingsAndReset(buf);
    env->SetFloatArrayRegion(out, 0, 5, buf);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetStageEnabled(JNIEnv* env, jobject, jint stage, jboolean enabled) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (gSlamEngine) gSlamEngine->setStageEnabled((int) stage, enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetRelocResult(JNIEnv* env, jobject, jfloatArray out) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine) return;
    // See nativeGetStageTimings above for why this is validated even though SetFloatArrayRegion
    // itself would already reject a short array.
    if (!out || env->GetArrayLength(out) < 19) {
        LOGE("nativeGetRelocResult: out array too short (need 19)");
        return;
    }
    float buf[19];
    gSlamEngine->getRelocResult(buf);
    env->SetFloatArrayRegion(out, 0, 19, buf);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetFingerprintAnchor(JNIEnv* env, jobject, jfloatArray out) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine) return;
    // See nativeGetStageTimings above for why this is validated even though SetFloatArrayRegion
    // itself would already reject a short array.
    if (!out || env->GetArrayLength(out) < 16) {
        LOGE("nativeGetFingerprintAnchor: out array too short (need 16)");
        return;
    }
    float buf[16];
    gSlamEngine->getFingerprintAnchor(buf);
    env->SetFloatArrayRegion(out, 0, 16, buf);
}

JNIEXPORT jbyteArray JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeExportFingerprint(
        JNIEnv* env, jobject thiz) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine) return nullptr;
    std::vector<uint8_t> fingerprint = gSlamEngine->exportFingerprint();
    if (fingerprint.empty()) return nullptr;

    jbyteArray result = env->NewByteArray((jsize)fingerprint.size());
    if (!result) return nullptr;
    env->SetByteArrayRegion(result, 0, (jsize)fingerprint.size(), (jbyte*)fingerprint.data());
    return result;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeAlignToFingerprint(
        JNIEnv* env, jobject thiz, jbyteArray data) {
    std::shared_lock<std::shared_mutex> engineLock(gEngineMutex);
    if (!gSlamEngine || !data) return;
    jsize size = env->GetArrayLength(data);
    jbyte* buffer = env->GetByteArrayElements(data, nullptr);

    try {
        gSlamEngine->alignToFingerprint((uint8_t*)buffer, size);
    } catch (const std::exception& e) {
        LOGE("nativeAlignToFingerprint: exception: %s", e.what());
    } catch (...) {
        LOGE("nativeAlignToFingerprint: unknown exception");
    }

    env->ReleaseByteArrayElements(data, buffer, JNI_ABORT);
}

// ── ARCore-unavailable fallback (HomographyTrackerNative.kt / HomographyTracker.h) ──────────────

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_HomographyTrackerNative_nativeHomographySetReference(
        JNIEnv* env, jobject thiz, jobject bitmap, jfloat objectHalfW, jfloat objectHalfH) {
    if (!bitmap) return JNI_FALSE;
    try {
        cv::Mat img;
        bitmapToMat(env, bitmap, img);
        return gHomographyTracker.setReference(img, objectHalfW, objectHalfH) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        LOGE("nativeHomographySetReference: exception: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("nativeHomographySetReference: unknown exception");
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_HomographyTrackerNative_nativeHomographyHasReference(
        JNIEnv* env, jobject thiz) {
    return gHomographyTracker.hasReference() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_HomographyTrackerNative_nativeHomographyReset(
        JNIEnv* env, jobject thiz) {
    gHomographyTracker.reset();
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_HomographyTrackerNative_nativeHomographyTrack(
        JNIEnv* env, jobject thiz, jobject bitmap, jfloat fx, jfloat fy, jfloat cx, jfloat cy,
        jfloatArray out) {
    if (!bitmap || !out || env->GetArrayLength(out) < 17) {
        LOGE("nativeHomographyTrack: bad args (out array too short — need 17)");
        return JNI_FALSE;
    }
    try {
        cv::Mat img;
        bitmapToMat(env, bitmap, img);
        float pose[16];
        float confidence = 0.0f;
        if (!gHomographyTracker.track(img, fx, fy, cx, cy, pose, &confidence)) return JNI_FALSE;
        float buf[17];
        std::memcpy(buf, pose, sizeof(pose));
        buf[16] = confidence;
        env->SetFloatArrayRegion(out, 0, 17, buf);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("nativeHomographyTrack: exception: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("nativeHomographyTrack: unknown exception");
        return JNI_FALSE;
    }
}

} // extern "C"
