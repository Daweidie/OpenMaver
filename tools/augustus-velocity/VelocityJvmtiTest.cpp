#include "../../core/jni/velocity_hooks.h"

// Same implementation as the attach agent, exercised in an isolated JVM.
extern "C" JNIEXPORT jboolean JNICALL Java_VelocityTransformTest_install(
        JNIEnv* env, jclass type, jobject transformer, jobjectArray targets, jobjectArray names) {
    return openmaver_velocity::installVelocityHooks(env,type,transformer,targets,names);
}
