cmake_minimum_required(VERSION 3.20)
project(quackwire CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Default to Release so a bare `cmake -B build` (no -DCMAKE_BUILD_TYPE) still
# produces an optimised library. CI matrix (Task 8) passes Release explicitly.
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

find_package(JNI REQUIRED)

add_library(quackwire SHARED src/quackwire.cpp)
target_include_directories(quackwire PRIVATE ${JNI_INCLUDE_DIRS})

# duckdb-quack message layer - three files only; rest excluded per design
# doc 9.4 because they pull in httplib / the HTTP server / scan extension.
set(DUCKDB_QUACK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/thirdparty/duckdb-quack)
target_sources(quackwire PRIVATE
  ${DUCKDB_QUACK_DIR}/src/quack_message.cpp
  ${DUCKDB_QUACK_DIR}/src/serialize_quack_message.cpp
  ${DUCKDB_QUACK_DIR}/src/quack_uri.cpp
)

target_include_directories(quackwire PRIVATE
  ${DUCKDB_QUACK_DIR}/src/include
)

# libduckdb 2.0.0 link target (duckdb-quack pin: 974927a, Quack protocol v3).
#
# CI assembles DUCKDB_HOME from the official release zips of the pinned
# DuckDB (project/Versions.scala) on ALL platforms (see
# .github/workflows/quackwire.yml). The system-package hints below remain for
# local dev, but verify the installed version first: a libduckdb ABI mismatch
# (e.g. Homebrew lagging the pin) can link cleanly and fail at runtime. Prefer
# pointing DUCKDB_HOME at the pinned .duckdb/<abi>/ cache or an unpacked release
# zip plus the internal-header tree. Whatever is linked is recorded in the
# build marker below, and the manager refuses to boot on a mismatch.
# DUCKDB_HOME (env var or -DDUCKDB_HOME=) points at an unpacked libduckdb dir.
# It is required on Windows (there is no system package): unzip
# libduckdb-windows-amd64.zip and populate it with duckdb.lib + the FULL duckdb/
# include tree. The release zip ships only the amalgamated duckdb.{h,hpp}; the
# duckdb-quack message layer also pulls internal headers such as
# duckdb/common/serializer/binary_serializer.hpp, so a shallow `git clone
# --branch v<pin>` of duckdb/src/include/duckdb must be copied alongside them
# (see .github/workflows/quackwire.yml for the exact staging). On macOS/Linux the
# system package hints below still apply, so DUCKDB_HOME is optional there.
find_path(DUCKDB_INCLUDE_DIR duckdb.hpp
  HINTS
    $ENV{DUCKDB_HOME} $ENV{DUCKDB_HOME}/include
    ${DUCKDB_HOME} ${DUCKDB_HOME}/include
    /opt/homebrew/include /usr/local/include /usr/include)
find_library(DUCKDB_LIB duckdb
  HINTS
    $ENV{DUCKDB_HOME} $ENV{DUCKDB_HOME}/lib
    ${DUCKDB_HOME} ${DUCKDB_HOME}/lib
    /opt/homebrew/lib /usr/local/lib /usr/lib)
if(NOT DUCKDB_INCLUDE_DIR OR NOT DUCKDB_LIB)
  message(FATAL_ERROR "libduckdb not found. Install via 'brew install duckdb' (macOS), 'apt install libduckdb-dev' (Linux), or unpack libduckdb-windows-amd64.zip and set DUCKDB_HOME to it (Windows). See .github/workflows/quackwire.yml for the exact layout.")
endif()
message(STATUS "quackwire: using libduckdb headers at ${DUCKDB_INCLUDE_DIR}")
message(STATUS "quackwire: linking libduckdb at ${DUCKDB_LIB}")
target_include_directories(quackwire PRIVATE ${DUCKDB_INCLUDE_DIR})
target_link_libraries(quackwire PRIVATE ${DUCKDB_LIB})

# Linux: bind every symbol at load. glibc binds lazily by default, so a native
# built against another libduckdb loads cleanly and the JVM dies with a "symbol
# lookup error" (_exit 127, no shutdown hooks) at the first call through a
# missing symbol. With -z now the missing symbol fails System.load instead, which
# QuackNativeSupport.bootCheck turns into a boot refusal. macOS chained fixups
# and Windows import tables already resolve at load.
if(UNIX AND NOT APPLE)
  target_link_options(quackwire PRIVATE "LINKER:-z,now")
endif()

# ---- build marker (quackwire.cpp kQuackwireBuildInfo) -----------------------
# Records what this binary is built against, for QuackNativeSupport.bootCheck
# and scripts/release/quackwire-build-info.sh to check against the pin.
#
# Protocol generation: parsed from the vendored quack_message.hpp; quackwire.cpp
# static_asserts it against QUACK_VERSION.
file(STRINGS ${DUCKDB_QUACK_DIR}/src/include/quack_message.hpp quack_version_line
  REGEX "QUACK_VERSION = [0-9]+;")
string(REGEX MATCH "[0-9]+" QUACKWIRE_PROTOCOL "${quack_version_line}")
if(NOT QUACKWIRE_PROTOCOL)
  message(FATAL_ERROR "cannot read QUACK_VERSION from ${DUCKDB_QUACK_DIR}/src/include/quack_message.hpp")
endif()

# quack submodule commit: from git unless given (-DQUACKWIRE_QUACK_SHA=<40 hex>,
# for a source tree without .git).
set(QUACKWIRE_QUACK_SHA "" CACHE STRING "duckdb-quack commit the build marker records (default: git)")
set(quack_sha "${QUACKWIRE_QUACK_SHA}")
if(NOT quack_sha)
  execute_process(COMMAND git -C ${DUCKDB_QUACK_DIR} rev-parse HEAD
    OUTPUT_VARIABLE quack_sha OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
endif()
if(NOT quack_sha MATCHES "^[0-9a-f]+$")
  message(FATAL_ERROR "cannot read the duckdb-quack commit at ${DUCKDB_QUACK_DIR} (git submodule update --init, or pass -DQUACKWIRE_QUACK_SHA=<sha>)")
endif()

# DuckDB version and source id: asked of the libduckdb being linked (a tiny
# program run at configure time), so the marker cannot name another DuckDB than
# the one the binary links. Cross builds that cannot run the probe pass both
# -DQUACKWIRE_DUCKDB_VERSION= and -DQUACKWIRE_DUCKDB_SOURCE_ID= instead. The
# probe needs the library at run time: an rpath on macOS/Linux (CMake adds the
# linked library's directory to build-tree binaries), the dll on PATH on Windows.
set(QUACKWIRE_DUCKDB_VERSION "" CACHE STRING "DuckDB version the build marker records (default: probe)")
set(QUACKWIRE_DUCKDB_SOURCE_ID "" CACHE STRING "DuckDB source id the build marker records (default: probe)")
set(duckdb_version "${QUACKWIRE_DUCKDB_VERSION}")
set(duckdb_source "${QUACKWIRE_DUCKDB_SOURCE_ID}")
if(NOT duckdb_version OR NOT duckdb_source)
  try_run(probe_run probe_compiled
    ${CMAKE_BINARY_DIR}/duckdb_version_probe
    ${CMAKE_CURRENT_SOURCE_DIR}/cmake/duckdb_version_probe.cpp
    CMAKE_FLAGS "-DINCLUDE_DIRECTORIES=${DUCKDB_INCLUDE_DIR}"
    LINK_LIBRARIES ${DUCKDB_LIB}
    CXX_STANDARD 17
    COMPILE_OUTPUT_VARIABLE probe_compile_out
    RUN_OUTPUT_VARIABLE probe_out)
  if(NOT probe_compiled OR NOT probe_run EQUAL 0)
    message(FATAL_ERROR "cannot probe the version of ${DUCKDB_LIB} (compiled=${probe_compiled}, run=${probe_run}); pass -DQUACKWIRE_DUCKDB_VERSION= and -DQUACKWIRE_DUCKDB_SOURCE_ID=.\n${probe_compile_out}")
  endif()
  string(STRIP "${probe_out}" probe_out)
  list(LENGTH probe_out probe_len)
  if(NOT probe_len EQUAL 2)
    message(FATAL_ERROR "unexpected DuckDB version probe output: ${probe_out}")
  endif()
  list(GET probe_out 0 duckdb_version)
  list(GET probe_out 1 duckdb_source)
endif()
if(NOT duckdb_version MATCHES "^v?[0-9]+\\.[0-9]+\\.[0-9]+" OR NOT duckdb_source MATCHES "^[0-9a-f]+$")
  message(FATAL_ERROR "implausible DuckDB version/source id: '${duckdb_version}' / '${duckdb_source}'")
endif()
message(STATUS "quackwire: build marker protocol=${QUACKWIRE_PROTOCOL} duckdb=${duckdb_version} duckdb_source=${duckdb_source} quack=${quack_sha}")
target_compile_definitions(quackwire PRIVATE
  QUACKWIRE_PROTOCOL=${QUACKWIRE_PROTOCOL}
  "QUACKWIRE_DUCKDB_VERSION=\"${duckdb_version}\""
  "QUACKWIRE_DUCKDB_SOURCE_ID=\"${duckdb_source}\""
  "QUACKWIRE_QUACK_SHA=\"${quack_sha}\"")

if(WIN32)
  # The prebuilt duckdb.dll exports only its DUCKDB_API surface. The DuckDB C++
  # serializer internals the duckdb-quack message layer relies on
  # (BinarySerializer / BinaryDeserializer / MemoryStream /
  # StorageCompatibility, SerializationCompatibility before 2.0) are NOT in that
  # export table, so on Windows they surface as `unresolved external symbol` at
  # link time - they resolve for free on Linux/macOS only because shared objects
  # export every symbol by default.
  #
  # Fix: compile those four sources straight from the vendored duckdb submodule
  # into the shim, and define DUCKDB_STATIC_BUILD so winapi.hpp expands DUCKDB_API
  # to empty. Without it the headers declare the serializer as __declspec(dllimport),
  # so callers emit __imp_ references the local definitions would never satisfy;
  # with it every DUCKDB_API symbol uses plain linkage - the locally compiled
  # serializer resolves directly, and the remaining duckdb symbols resolve through
  # duckdb.lib's function import thunks.
  #
  # WIN32-only on purpose: on Linux/macOS libduckdb already defines these, so
  # compiling them here too would be a duplicate-symbol (multiple definition) error.
  target_compile_definitions(quackwire PRIVATE DUCKDB_STATIC_BUILD)
  set(DUCKDB_SERIALIZER_DIR ${DUCKDB_QUACK_DIR}/duckdb/src/common/serializer)
  target_sources(quackwire PRIVATE
    ${DUCKDB_SERIALIZER_DIR}/binary_serializer.cpp
    ${DUCKDB_SERIALIZER_DIR}/binary_deserializer.cpp
    ${DUCKDB_SERIALIZER_DIR}/memory_stream.cpp
    ${DUCKDB_SERIALIZER_DIR}/serializer.cpp
    # A couple of leaf symbols the serializer references
    # (StorageCompatibility, ValueOperations::NotDistinctFrom) live in
    # heavy translation units (storage_compatibility.cpp, which reaches the
    # storage manager, and comparison_operations.cpp); replicate
    # just those here instead of compiling all of DuckDB.
    ${CMAKE_CURRENT_SOURCE_DIR}/src/win_duckdb_compat.cpp
  )
endif()

if(APPLE)
  set_target_properties(quackwire PROPERTIES SUFFIX ".dylib")
endif()