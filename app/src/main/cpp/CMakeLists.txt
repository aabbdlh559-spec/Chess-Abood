cmake_minimum_required(VERSION 3.22.1)
project(chess_abood_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(abood_jni SHARED engine.cpp)
target_compile_definitions(abood_jni PRIVATE BUILD_JNI_LIB=1)
find_library(log-lib log)
target_link_libraries(abood_jni ${log-lib})

add_executable(abood_engine engine.cpp)
set_target_properties(abood_engine PROPERTIES
    OUTPUT_NAME "abood_engine"
    PREFIX "lib"
    SUFFIX ".so"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)
