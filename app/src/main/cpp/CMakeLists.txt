# NDK Protection Library CMake Configuration

cmake_minimum_required(VERSION 3.22.1)
project("protection")

add_library(protection SHARED protection.cpp)

target_link_libraries(protection android log)

add_library(security_check SHARED protection.cpp)
target_link_libraries(security_check android log)

add_custom_command(TARGET security_check POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory
        ${CMAKE_SOURCE_DIR}/../assets/lib/${ANDROID_ABI}
    COMMAND ${CMAKE_COMMAND} -E copy
        $<TARGET_FILE:security_check>
        ${CMAKE_SOURCE_DIR}/../assets/lib/${ANDROID_ABI}/libsecurity_check.so
)
