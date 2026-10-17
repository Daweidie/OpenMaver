# ---------------------------------------------------------------------------
# OpenMaver 核心本地库
# 产物：libopenmaver_core.so（供 Java 端 System.loadLibrary("openmaver_core") 加载）
# ---------------------------------------------------------------------------

# 查找 JNI 头文件与库（需要安装 JDK）
find_package(JNI REQUIRED)

add_library(openmaver_core SHARED
    jni/bridge.cpp
    jni/agent.cpp
)

# JNI 头文件目录 + 本模块头文件目录
target_include_directories(openmaver_core
    PRIVATE
        ${JNI_INCLUDE_DIRS}
        ${CMAKE_CURRENT_SOURCE_DIR}/jni
)

# 链接 JNI 库
target_link_libraries(openmaver_core
    PRIVATE
        ${JNI_LIBRARIES}
)

# 显式关闭异常与 RTTI（与根工程保持一致，便于单独构建本目录）
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(openmaver_core PRIVATE -fno-exceptions -fno-rtti)
elseif(MSVC)
    target_compile_options(openmaver_core PRIVATE /EHs-c- /GR-)
endif()

# 保证生成 libopenmaver_core.so
set_target_properties(openmaver_core PROPERTIES
    OUTPUT_NAME "openmaver_core"
    PREFIX "lib"
    POSITION_INDEPENDENT_CODE ON
)
