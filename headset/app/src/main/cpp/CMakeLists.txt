cmake_minimum_required(VERSION 3.22.1)
project(roomsplat_rays LANGUAGES CXX)
add_library(roomsplat_rays SHARED mesh_rays.cpp)
target_compile_features(roomsplat_rays PRIVATE cxx_std_17)
# JNI kernel is also used in debug APKs; no per-cell managed allocations.
target_compile_options(roomsplat_rays PRIVATE -O3 -ffp-contract=off -Wall -Wextra -Werror)
