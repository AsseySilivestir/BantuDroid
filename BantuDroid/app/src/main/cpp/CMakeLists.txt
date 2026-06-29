cmake_minimum_required(VERSION 3.18.1)

project("banturun")

# JNI Bridge library - allows executing Bantu binary via fork+execv
# This bypasses Android's SELinux restrictions on Java ProcessBuilder.exec()
add_library(banturun SHARED bantu_jni.cpp)

# Link against Android log library
find_library(log-lib log)

target_link_libraries(banturun ${log-lib})

# Set C++17 standard
set_target_properties(banturun PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)
