cmake_minimum_required(VERSION 3.22.1)
project(oriva_quickjs C)

# Compile only the engine. quickjs-libc and its filesystem/network helpers are deliberately absent.
add_library(oriva_quickjs SHARED oriva_quickjs.c
    quickjs/quickjs.c quickjs/cutils.c quickjs/libregexp.c quickjs/libunicode.c quickjs/dtoa.c)
target_include_directories(oriva_quickjs PRIVATE quickjs)
target_compile_features(oriva_quickjs PRIVATE c_std_11)
target_compile_definitions(oriva_quickjs PRIVATE _GNU_SOURCE CONFIG_VERSION="2026-06-04")
target_compile_options(oriva_quickjs PRIVATE -Os -fwrapv -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(oriva_quickjs PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
target_link_libraries(oriva_quickjs PRIVATE m)
