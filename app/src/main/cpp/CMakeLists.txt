cmake_minimum_required(VERSION 3.22.1)
project(tvplayair_native LANGUAGES CXX)

add_library(tvplayair_native SHARED native-lib.cpp)

find_package(oboe REQUIRED CONFIG)

target_link_libraries(tvplayair_native
    oboe::oboe
    android
    log
)
