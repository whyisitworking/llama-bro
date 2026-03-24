cmake_minimum_required(VERSION 3.22.1)
project(llama_bro C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# Needed for multiple CPU variants
set(GGML_BACKEND_DL ON)

# CPU is the most stable backend on mobile now
set(GGML_CPU ON)

# Compile GGML for all CPU variants
set(GGML_CPU_ALL_VARIANTS ON)

# OpenMP mismanages big.LITTLE architectures
set(GGML_OPENMP OFF)

# Use optimized kernels
set(GGML_CPU_KLEIDIAI ON)

add_subdirectory(external/llama.cpp)

add_library(llama_bro
    SHARED
        engine/engine.cpp
        session/session.cpp

        jni/engine.cpp
        jni/session.cpp
)

target_include_directories(llama_bro
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}

    SYSTEM PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/external/llama.cpp
)

target_link_libraries(llama_bro PRIVATE llama log)
