cmake_minimum_required(VERSION 3.22.1)
project(srx_native)

add_library(srx_native SHARED native_hook.cpp)

find_library(log-lib log)

target_link_libraries(srx_native
    ${log-lib}
    dl
)
