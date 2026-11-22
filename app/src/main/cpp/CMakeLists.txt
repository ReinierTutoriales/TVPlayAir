cmake_minimum_required(VERSION 3.22.1)
project(tvplayair_native LANGUAGES C CXX)

add_library(fairplay_v3 STATIC
    fairplay_v3/hand_garble.c
    fairplay_v3/modified_md5.c
    fairplay_v3/omg_hax.c
    fairplay_v3/playfair.c
    fairplay_v3/sap_hash.c
)
target_include_directories(fairplay_v3 PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/fairplay_v3)

add_library(alac_codec STATIC
    alac/ALACBitUtilities.c
    alac/ALACDecoder.cpp
    alac/EndianPortable.c
    alac/ag_dec.c
    alac/dp_dec.c
    alac/matrix_dec.c
)
target_include_directories(alac_codec PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/alac)

add_library(tvplayair_native SHARED
    native-lib.cpp
    oboe_consumer.cpp
    session_producer.cpp
    fairplay_jni.cpp
)
target_compile_features(tvplayair_native PRIVATE cxx_std_17)
target_include_directories(tvplayair_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_package(oboe REQUIRED CONFIG)
target_link_libraries(tvplayair_native fairplay_v3 alac_codec oboe::oboe android log m)
