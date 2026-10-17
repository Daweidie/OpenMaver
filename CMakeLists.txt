# ---------------------------------------------------------------------------
# OpenMaver V2.0 —— 顶层构建脚本
#
# 技术栈：C++17（关闭异常/RTTI） + Java 8 + JNI，使用 CMake 构建。
#
# 典型用法（仅核心，独立运行）：
#   cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
#   cmake --build build -j
#
# 附带 Forge 1.8.9 集成（需要提供 MC/Forge 开发环境 classpath）：
#   cmake -S . -B build -DCMAKE_BUILD_TYPE=Release \
#         -DOPENMAVER_MC_CLASSPATH="/path/forge-1.8.9-universal.jar;/path/minecraft-1.8.9-deobf.jar"
#   cmake --build build -j
# ---------------------------------------------------------------------------

cmake_minimum_required(VERSION 3.16)

project(OpenMaver VERSION 2.0.0 LANGUAGES CXX)

# —— C++ 标准 ——
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# —— 默认构建类型 ——
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE "Release" CACHE STRING "构建类型" FORCE)
endif()

# —— 关闭异常与 RTTI ——
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    add_compile_options(-fno-exceptions -fno-rtti)
    # 默认隐藏符号，JNI 函数通过 OM_EXPORT 单独导出
    add_compile_options(-fvisibility=hidden)
    add_compile_options(-Wall -Wextra)
elseif(MSVC)
    add_compile_options(/EHs-c- /GR-)
endif()

# —— 统一输出目录 ——
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")

# —— 核心本地库 ——
add_subdirectory(core)

# ===========================================================================
# 自研注入器（C++ / POSIX）：把 native agent(.so) 运行期注入到目标 JVM
# 产物：build/bin/openmaver-injector
# ===========================================================================
if(UNIX)
    add_executable(openmaver_injector inject/injector.cpp)
    target_compile_options(openmaver_injector PRIVATE -Wall -Wextra)
    target_compile_features(openmaver_injector PRIVATE cxx_std_17)
endif()

# ===========================================================================
# Java：核心代码（始终编译，目标字节码 Java 8，兼容 MC 1.8.9 的 JRE）
# ===========================================================================
find_package(Java COMPONENTS Runtime Development)

if(Java_Development_FOUND)

    # 探测 javac 是否支持 --release（JDK 9+ 才有；JDK 8 需退回 -source/-target）
    execute_process(
        COMMAND "${Java_JAVAC_EXECUTABLE}" --help
        OUTPUT_VARIABLE OPENMAVER_JAVAC_HELP
        ERROR_VARIABLE OPENMAVER_JAVAC_HELP
    )
    if(OPENMAVER_JAVAC_HELP MATCHES "--release")
        set(OPENMAVER_JAVA_RELEASE_FLAGS "--release" "8" "-Xlint:-options")
    else()
        set(OPENMAVER_JAVA_RELEASE_FLAGS "-source" "8" "-target" "8" "-Xlint:-options")
    endif()

    file(GLOB_RECURSE OPENMAVER_JAVA_SOURCES CONFIGURE_DEPENDS
        "${CMAKE_SOURCE_DIR}/java/*.java")

    set(OPENMAVER_JAVA_CLASSES_DIR "${CMAKE_BINARY_DIR}/java-classes")

    add_custom_target(openmaver_java ALL
        COMMAND ${CMAKE_COMMAND} -E make_directory "${OPENMAVER_JAVA_CLASSES_DIR}"
        COMMAND "${Java_JAVAC_EXECUTABLE}"
                ${OPENMAVER_JAVA_RELEASE_FLAGS}
                -encoding UTF-8
                -d "${OPENMAVER_JAVA_CLASSES_DIR}"
                ${OPENMAVER_JAVA_SOURCES}
        WORKING_DIRECTORY "${CMAKE_SOURCE_DIR}/java"
        COMMENT "编译 OpenMaver Java 核心 (Java 8 字节码)"
        VERBATIM
    )

    # Java 运行期需要本地库，保证构建顺序
    add_dependencies(openmaver_java openmaver_core)

    # =======================================================================
    # Java：Forge 1.8.9 集成
    #   mc/(MCP 名) --javac--> mc-classes --reobf(MCP->SRG)--> mc-classes-reobf
    #
    # 编译用的 API：
    #   - 默认用仓库内置的 mcstubs/（编译期 stub，无需 Forge 开发环境，不进 jar）
    #   - 也可用 -DOPENMAVER_MC_CLASSPATH=... 指向真实 Forge/MC deobf jar
    # 为什么必须 reobf：Forge 1.8.9 运行期用 SRG 名（func_/field_），
    #   javac 编出来的是 MCP 名（getMinecraft/fontRendererObj），不改会 NoSuchMethodError。
    #
    # 产物：
    #   build/mc-classes-reobf/   运行期可用的 SRG 类
    #   build/openmaver-mc.jar    仅 mc 类（运行期注入用）
    #   build/openmaver-mod.jar   完整 mod（mc 类 + 核心类 + mcmod.info + native）
    # =======================================================================
    set(OPENMAVER_MC_CLASSPATH "" CACHE STRING
        "Forge 1.8.9 开发环境 classpath（CMake 列表）。留空则用仓库内置 mcstubs 编译。")

    if(WIN32)
        set(OPENMAVER_CP_SEP ";")
    else()
        set(OPENMAVER_CP_SEP ":")
    endif()

    set(OPENMAVER_MC_CLASSES_DIR "${CMAKE_BINARY_DIR}/mc-classes")
    set(OPENMAVER_MC_REOBF_DIR "${CMAKE_BINARY_DIR}/mc-classes-reobf")
    set(OPENMAVER_MC_JAR "${CMAKE_BINARY_DIR}/openmaver-mc.jar")
    set(OPENMAVER_MOD_JAR "${CMAKE_BINARY_DIR}/openmaver-mod.jar")
    set(OPENMAVER_MOD_STAGE "${CMAKE_BINARY_DIR}/mod-stage")

    file(GLOB_RECURSE OPENMAVER_MC_SOURCES CONFIGURE_DEPENDS
        "${CMAKE_SOURCE_DIR}/mc/*.java")

    # mc/ 的编译类路径
    if(NOT OPENMAVER_MC_CLASSPATH STREQUAL "")
        string(REPLACE ";" "${OPENMAVER_CP_SEP}" OPENMAVER_MC_CP_JOINED "${OPENMAVER_MC_CLASSPATH}")
        set(OPENMAVER_MC_CP "${OPENMAVER_MC_CP_JOINED}${OPENMAVER_CP_SEP}${OPENMAVER_JAVA_CLASSES_DIR}")
        message(STATUS "mc/ 使用外部 classpath 编译: ${OPENMAVER_MC_CLASSPATH}")
    else()
        set(OPENMAVER_MCSTUB_CLASSES_DIR "${CMAKE_BINARY_DIR}/mcstubs-classes")
        file(GLOB_RECURSE OPENMAVER_MCSTUB_SOURCES CONFIGURE_DEPENDS
            "${CMAKE_SOURCE_DIR}/mcstubs/*.java")
        add_custom_target(openmaver_mcstubs ALL
            COMMAND ${CMAKE_COMMAND} -E make_directory "${OPENMAVER_MCSTUB_CLASSES_DIR}"
            COMMAND "${Java_JAVAC_EXECUTABLE}" ${OPENMAVER_JAVA_RELEASE_FLAGS} -encoding UTF-8
                    -d "${OPENMAVER_MCSTUB_CLASSES_DIR}" ${OPENMAVER_MCSTUB_SOURCES}
            COMMENT "编译 mc/ 的 API stub（编译期用，不进 jar）"
            VERBATIM
        )
        set(OPENMAVER_MC_CP "${OPENMAVER_MCSTUB_CLASSES_DIR}${OPENMAVER_CP_SEP}${OPENMAVER_JAVA_CLASSES_DIR}")
        message(STATUS "mc/ 使用仓库内置 mcstubs 编译")
    endif()

    add_custom_target(openmaver_mc ALL
        COMMAND ${CMAKE_COMMAND} -E make_directory "${OPENMAVER_MC_CLASSES_DIR}"
        COMMAND "${Java_JAVAC_EXECUTABLE}" ${OPENMAVER_JAVA_RELEASE_FLAGS}
                -encoding UTF-8 -cp "${OPENMAVER_MC_CP}"
                -d "${OPENMAVER_MC_CLASSES_DIR}" ${OPENMAVER_MC_SOURCES}
        WORKING_DIRECTORY "${CMAKE_SOURCE_DIR}/mc"
        COMMENT "编译 OpenMaver Forge 1.8.9 集成 (MCP 名)"
        VERBATIM
    )
    add_dependencies(openmaver_mc openmaver_java)
    if(DEFINED OPENMAVER_MCSTUB_CLASSES_DIR)
        add_dependencies(openmaver_mc openmaver_mcstubs)
    endif()

    # reobf 工具
    add_custom_target(openmaver_reobf_tool ALL
        COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_BINARY_DIR}/tools"
        COMMAND "${Java_JAVAC_EXECUTABLE}" -d "${CMAKE_BINARY_DIR}/tools"
                "${CMAKE_SOURCE_DIR}/tools/McpReobf.java"
        COMMENT "编译 reobf 工具"
        VERBATIM
    )

    # mc-classes --reobf--> mc-classes-reobf
    add_custom_target(openmaver_mc_reobf ALL
        COMMAND "${Java_JAVA_EXECUTABLE}" -cp "${CMAKE_BINARY_DIR}/tools"
                McpReobf "${OPENMAVER_MC_CLASSES_DIR}" "${OPENMAVER_MC_REOBF_DIR}"
        DEPENDS openmaver_mc openmaver_reobf_tool
        COMMENT "reobf: MCP -> SRG（运行期可用）"
        VERBATIM
    )

    # 仅 mc 类（供运行期注入）
    add_custom_target(openmaver_mc_jar ALL
        COMMAND "${Java_JAR_EXECUTABLE}" cf "${OPENMAVER_MC_JAR}"
                -C "${OPENMAVER_MC_REOBF_DIR}" .
        DEPENDS openmaver_mc_reobf
        COMMENT "打包 openmaver-mc.jar（运行期注入用）"
        VERBATIM
    )

    # native 目录 os/arch
    if(WIN32)
        set(OPENMAVER_NATIVE_OS "windows")
    elseif(APPLE)
        set(OPENMAVER_NATIVE_OS "macos")
    else()
        set(OPENMAVER_NATIVE_OS "linux")
    endif()
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|amd64|AMD64")
        set(OPENMAVER_NATIVE_ARCH "x64")
    elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64|ARM64")
        set(OPENMAVER_NATIVE_ARCH "arm64")
    elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "i386|i686|x86")
        set(OPENMAVER_NATIVE_ARCH "x86")
    else()
        set(OPENMAVER_NATIVE_ARCH "${CMAKE_SYSTEM_PROCESSOR}")
    endif()
    set(OPENMAVER_NATIVE_DIR "${OPENMAVER_MOD_STAGE}/native/${OPENMAVER_NATIVE_OS}/${OPENMAVER_NATIVE_ARCH}")

    # 完整 mod（丢进 mods/；本地库随 jar 分发）
    add_custom_target(openmaver_mod ALL
        COMMAND ${CMAKE_COMMAND} -E make_directory "${OPENMAVER_NATIVE_DIR}"
        COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:openmaver_core>" "${OPENMAVER_NATIVE_DIR}/"
        COMMAND ${CMAKE_COMMAND} -E copy "${CMAKE_SOURCE_DIR}/mc/mcmod.info" "${OPENMAVER_MOD_STAGE}/mcmod.info"
        COMMAND "${Java_JAR_EXECUTABLE}" cf "${OPENMAVER_MOD_JAR}"
                -C "${OPENMAVER_MC_REOBF_DIR}" .
                -C "${OPENMAVER_JAVA_CLASSES_DIR}" .
                -C "${OPENMAVER_MOD_STAGE}" .
        DEPENDS openmaver_mc_reobf openmaver_core
        COMMENT "打包 openmaver-mod.jar（可直接放入 mods/）"
        VERBATIM
    )
    message(STATUS "mod jar -> ${OPENMAVER_MOD_JAR} ; 注入用 mc jar -> ${OPENMAVER_MC_JAR}")

    # =======================================================================
    # 运行期注入：核心类 jar
    #
    # JVMTI agent（libopenmaver_core.so 的 Agent_OnAttach）用 jar= 参数把该 jar
    # 加入 bootstrap 类搜索路径，从而在目标进程内加载 OpenMaver 核心类。
    # 产物：build/openmaver-core.jar
    # =======================================================================
    set(OPENMAVER_CORE_JAR "${CMAKE_BINARY_DIR}/openmaver-core.jar")

    # 用 custom_target（每次构建都重新打包），避免新增类文件后旧 jar 不更新
    add_custom_target(openmaver_core_jar ALL
        COMMAND "${Java_JAR_EXECUTABLE}" cf "${OPENMAVER_CORE_JAR}"
                -C "${OPENMAVER_JAVA_CLASSES_DIR}" .
        DEPENDS openmaver_java
        COMMENT "打包 openmaver-core.jar（供 JVMTI agent 加入 bootstrap 搜索路径）"
        VERBATIM
    )

else()
    message(STATUS "未找到 JDK (Java Development)，跳过 Java 编译；仅构建本地库。")
endif()
