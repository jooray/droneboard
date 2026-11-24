# SPDX-License-Identifier: GPL-3.0-only
# libdroneswipe.so: FUTO Swipe engine (swipe-library + ExecuTorch) plus Droneboard's key-path
# dictionary and JNI. Built by tools/build-swipe-lib.sh; the result is committed as a prebuilt
# library in app/src/main/jniLibs/<abi>/.
cmake_minimum_required(VERSION 3.17)
project(droneswipe LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT SWIPE_LIBRARY_DIR)
    message(FATAL_ERROR "Set -DSWIPE_LIBRARY_DIR=<path to a built FUTO swipe-library checkout>")
endif()

# provides swipe_decoder (and the "executorch" interface target for the prebuilt static libs
# that the swipe-library Makefile builds into third_party/executorch/cmake-out-<abi>)
set(EXECUTORCH_ROOT "${SWIPE_LIBRARY_DIR}/third_party/executorch" CACHE PATH "ExecuTorch source root")
add_subdirectory(${SWIPE_LIBRARY_DIR} swipe-library EXCLUDE_FROM_ALL)
if(NOT TARGET executorch)
    message(FATAL_ERROR "ExecuTorch for ${ANDROID_ABI} is not built in ${SWIPE_LIBRARY_DIR}")
endif()

find_package(Threads REQUIRED)
add_library(droneswipe SHARED droneswipe_jni.cpp kp_dictionary.cpp ranker.cpp neural_lm.cpp)
target_link_libraries(droneswipe PRIVATE swipe_decoder executorch log Threads::Threads)
target_compile_options(droneswipe PRIVATE -O3 -fno-rtti -fvisibility=hidden)
# 16 KiB page size support (Android 15+ devices)
target_link_options(droneswipe PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections" "-Wl,--exclude-libs,ALL")
