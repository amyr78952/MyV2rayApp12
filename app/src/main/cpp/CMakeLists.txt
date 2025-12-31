cmake_minimum_required(VERSION 3.18.1)
project(hev_jni_project)

add_library(hev_jni SHARED
        hev_tunnel_jni.cpp
)

find_library(log-lib log)

target_link_libraries(hev_jni
        ${log-lib}
)
