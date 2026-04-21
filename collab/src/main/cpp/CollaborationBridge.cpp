#include <jni.h>
#include <vector>
#include <cstdint>

// Forward declaration of MobileGS functions (from your existing native engine)
namespace mobilegs {
    std::vector<uint8_t> exportFingerprint();
    void alignToFingerprint(const uint8_t* data, size_t size);
}

extern "C" {

/**
 * Native bridge for AR Collaboration.
 * Wraps visual relocalization logic for the MobileGS engine.
 */

JNIEXPORT jbyteArray JNICALL
Java_com_hereliesaz_graffitixr_core_collaboration_CollaborationManager_nativeExportFingerprint(
        JNIEnv* env, jobject thiz) {

    std::vector<uint8_t> fingerprint = mobilegs::exportFingerprint();

    jbyteArray result = env->NewByteArray(fingerprint.size());
    env->SetByteArrayRegion(result, 0, fingerprint.size(), (jbyte*)fingerprint.data());

    return result;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_core_collaboration_CollaborationManager_nativeAlignToPeer(
        JNIEnv* env, jobject thiz, jbyteArray data) {

    jsize size = env->GetArrayLength(data);
    jbyte* buffer = env->GetByteArrayElements(data, nullptr);

    mobilegs::alignToFingerprint((uint8_t*)buffer, size);

    env->ReleaseByteArrayElements(data, buffer, JNI_ABORT);
}

}
