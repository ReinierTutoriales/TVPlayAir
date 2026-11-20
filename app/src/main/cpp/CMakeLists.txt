cmake_minimum_required(VERSION 3.22.1)
project(tvplayair_native LANGUAGES CXX)

add_library(tvplayair_native SHARED native-lib.cpp oboe_consumer.cpp)
target_compile_features(tvplayair_native PRIVATE cxx_std_17)
target_include_directories(tvplayair_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_package(oboe REQUIRED CONFIG)
target_link_libraries(tvplayair_native oboe::oboe android log)
