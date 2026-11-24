cmake_minimum_required(VERSION 3.22.1)
project(wp09 LANGUAGES CXX)

add_library(wp09 SHARED wp09.cpp thermal_dump.cpp)
target_compile_features(wp09 PRIVATE cxx_std_17)
target_compile_options(wp09 PRIVATE -Wall -Wextra -Werror)
target_link_options(wp09 PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(wp09 PRIVATE dl)
