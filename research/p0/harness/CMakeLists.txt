# Added into a llama.cpp build copy's tools/server/CMakeLists.txt by scripts/add-harness.sh, so that
# lb-spike is compiled with exactly the same flags, and links the same server-context, as llama-server.
add_executable(lb-spike lb-spike.cpp)
target_include_directories(lb-spike PRIVATE ${LB_SERVER_DIR} ${LB_SERVER_DIR}/../mtmd ${PROJECT_SOURCE_DIR})
target_link_libraries(lb-spike PRIVATE server-context ${CMAKE_THREAD_LIBS_INIT})
target_compile_features(lb-spike PRIVATE cxx_std_17)
