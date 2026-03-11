cmake_minimum_required(VERSION 3.22.1)
project(llama-bro C CXX)

# Enforce C11 for GGML
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Enforce C++17 for llama.cpp
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Disable OpenCL as it causes UI freezes
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS OFF CACHE BOOL "" FORCE)

# Need OpenMP for multithreading
set(GGML_OPENMP ON CACHE BOOL "" FORCE)

# Need multiple backends for runtime selection based on support
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)

# Need dynamic linkage for choosing backend
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)

# No point in building standalone
set(GGML_STANDALONE OFF CACHE BOOL "" FORCE)

add_subdirectory(external/llama.cpp)

add_library(llama-bro SHARED lb-interop.cpp)
target_link_libraries(llama-bro PRIVATE llama log)
