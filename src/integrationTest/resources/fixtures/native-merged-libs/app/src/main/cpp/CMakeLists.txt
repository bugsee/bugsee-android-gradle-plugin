cmake_minimum_required(VERSION 3.22.1)
project(bugseefixture C)
# -g keeps DWARF in the unstripped library AGP merges (the thing under test).
add_library(bugseefixture SHARED native.c)
target_compile_options(bugseefixture PRIVATE -g)
