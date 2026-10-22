cmake_minimum_required(VERSION 3.18.1)

project(ft8parallel C)

set(FT8LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ft8lib)

add_library(ft8parallel SHARED
        ${FT8LIB_DIR}/jni_parallel.c
        ${FT8LIB_DIR}/common/monitor.c
        ${FT8LIB_DIR}/fft/kiss_fft.c
        ${FT8LIB_DIR}/fft/kiss_fftr.c
        ${FT8LIB_DIR}/ft8/constants.c
        ${FT8LIB_DIR}/ft8/crc.c
        ${FT8LIB_DIR}/ft8/decode.c
        ${FT8LIB_DIR}/ft8/encode.c
        ${FT8LIB_DIR}/ft8/ldpc.c
        ${FT8LIB_DIR}/ft8/message.c
        ${FT8LIB_DIR}/ft8/text.c
)

target_include_directories(ft8parallel PRIVATE ${FT8LIB_DIR})
target_compile_options(ft8parallel PRIVATE -O3 -DHAVE_STPCPY)

find_library(log-lib log)
target_link_libraries(ft8parallel ${log-lib} m)
