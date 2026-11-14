# SPDX-License-Identifier: GPL-3.0-only
# Desktop replay of recorded swipes through the keyboard's exact decoder and ranking.
#   cmake -S tools/swipe-replay -B build/swipe-replay -DSWIPE_LIBRARY_DIR=<swipe-library with host ExecuTorch>
#   cmake --build build/swipe-replay -j
# SWIPE_LIBRARY_DIR: a checkout of https://gitlab.futo.org/keyboard/swipe-library at the revision in
# tools/build-swipe-lib.sh, with ExecuTorch built for the host (`make host` in that checkout).
cmake_minimum_required(VERSION 3.17)
project(swipe_replay LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

if(NOT SWIPE_LIBRARY_DIR)
    message(FATAL_ERROR "Set -DSWIPE_LIBRARY_DIR=<path to a built FUTO swipe-library checkout>")
endif()
set(EXECUTORCH_ROOT "${SWIPE_LIBRARY_DIR}/third_party/executorch" CACHE PATH "ExecuTorch source root")
add_subdirectory(${SWIPE_LIBRARY_DIR} swipe-library EXCLUDE_FROM_ALL)
# target_link_executorch() reads these from the caller's scope
foreach(v ET_INCLUDE_DIRS ET_WHOLE_ARCHIVE_LIBS ET_REGULAR_LIBS)
    get_directory_property(${v} DIRECTORY ${SWIPE_LIBRARY_DIR} DEFINITION ${v})
endforeach()

set(DRONESWIPE ${CMAKE_CURRENT_SOURCE_DIR}/../../native/droneswipe)
add_executable(swipe-replay swipe_replay.cpp ${DRONESWIPE}/kp_dictionary.cpp ${DRONESWIPE}/ranker.cpp)
target_include_directories(swipe-replay PRIVATE ${DRONESWIPE}
    ${SWIPE_LIBRARY_DIR}/third_party/executorch/third-party/json/include)
target_link_libraries(swipe-replay PRIVATE swipe_decoder)
target_link_executorch(swipe-replay)
target_compile_options(swipe-replay PRIVATE -fno-rtti)

# next-word prediction / completion of the n-gram language model (tap typing)
add_executable(lm-eval lm_eval.cpp ${DRONESWIPE}/kp_dictionary.cpp ${DRONESWIPE}/ranker.cpp ${DRONESWIPE}/neural_lm.cpp)
target_include_directories(lm-eval PRIVATE ${DRONESWIPE}
    ${SWIPE_LIBRARY_DIR}/third_party/executorch/third-party/json/include)
target_link_libraries(lm-eval PRIVATE swipe_decoder)
target_link_executorch(lm-eval)
target_compile_options(lm-eval PRIVATE -fno-rtti)

# numerical check / micro-benchmark of the neural LM runtime (tools/lm/verify_keyboard_lm.py)
add_executable(lm-check lm_check.cpp ${DRONESWIPE}/kp_dictionary.cpp ${DRONESWIPE}/neural_lm.cpp)
target_include_directories(lm-check PRIVATE ${DRONESWIPE}
    ${SWIPE_LIBRARY_DIR}/third_party/executorch/third-party/json/include)
target_link_libraries(lm-check PRIVATE swipe_decoder)
target_compile_options(lm-check PRIVATE -fno-rtti)
