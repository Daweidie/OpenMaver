#include "../../core/jni/velocity_hooks.h"

extern "C" JNIEXPORT jboolean JNICALL Java_FastPlaceTransformTest_install(
        JNIEnv* env, jclass type, jobject transformer, jobjectArray targets, jobjectArray names) {
    return openmaver_velocity::installVelocityHooks(env, type, transformer, targets, names);
}
