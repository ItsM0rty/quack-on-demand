// Windows-only DuckDB internal-symbol shims.
//
// ============================================================================
//  !! VERSION-PINNED FILE - REVISIT ON EVERY DUCKDB / duckdb-quack BUMP !!
//
//  Pinned to: DuckDB v2.0.0 (build.sbt libquackwireVersion
//             "2.0.0-f96420ed46ae-*"), re-derived 2026-10-13 from the
//             pre-release engine b15358c371 and re-checked 2026-10-21 against
//             the v2.0.0 tag: storage_compatibility.{hpp,cpp} and
//             storage_info.hpp are unchanged, and so are the serializer
//             sources of duckdb-quack's duckdb submodule (8d68e6f9d8) against
//             v2.0.0. Compiled and linked only by quackwire.yml's Windows leg.
//
//  DuckDB 2.0 renamed SerializationCompatibility to StorageCompatibility
//  (src/include/duckdb/common/storage_compatibility.hpp, its own translation
//  unit src/common/storage_compatibility.cpp, which reaches the attached
//  database and storage manager), indexed by the StorageVersion enum instead
//  of an idx_t serialization version. The class carries no DUCKDB_API, so
//  duckdb.dll does not export it. LogicalType's copy constructor and
//  destructor became inline in 2.0 (types.hpp), so they no longer need an
//  export either.
//
//  The bodies below are COPIED from the pinned DuckDB source. When you bump the
//  duckdb-quack submodule / libduckdb ABI in build.sbt, re-derive them or the
//  Windows quackwire.dll will link against stale constants (silent wire-format
//  mismatch with the node), or fail to link with a fresh batch of unresolved
//  externals. To re-derive at the new pin <SHA>:
//    * StorageCompatibility::{Default,FromIndex,Compare}
//        src/common/storage_compatibility.cpp
//    * the default storage version
//        src/include/duckdb/storage/storage_info.hpp
//        (StorageVersionInfo::GetStorageVersionDefault, constexpr)
//    * ValueOperations::NotDistinctFrom
//        src/common/value_operations/comparison_operations.cpp
//  If the Windows link surfaces NEW unresolved duckdb symbols, add their leaf
//  definitions here the same way (or compile their source if self-contained).
//  The serializer sources CMakeLists.txt compiles come from duckdb-quack's own
//  duckdb submodule: check it vendors the same engine as the pin.
// ============================================================================
//
// Why this file exists: the vendored duckdb serializer sources we compile on
// Windows (see CMakeLists.txt) reference a few DuckDB internals that the
// prebuilt duckdb.dll does not export and whose real source files
// (src/common/storage_compatibility.cpp, comparison_operations.cpp) would drag
// in most of DuckDB if compiled. Kept WIN32-only via CMakeLists.txt; on Linux/macOS libduckdb
// already provides these.
//
// DUCKDB_STATIC_BUILD is defined for the whole Windows build, so DUCKDB_API is
// empty here and these definitions have plain linkage matching the callers.

#include "duckdb/common/storage_compatibility.hpp"
#include "duckdb/common/value_operations/value_operations.hpp"
#include "duckdb/storage/storage_info.hpp"

namespace duckdb {

// src/common/storage_compatibility.cpp, release-build branch (neither
// DUCKDB_ALTERNATIVE_VERIFY nor DUCKDB_LATEST_STORAGE): FromIndex(the default
// storage version) relabelled "latest". Written out instead of calling FromIndex
// so the label does not need GetStorageVersionString (see below).
StorageCompatibility StorageCompatibility::Default() {
	StorageCompatibility result;
	result.storage_version = StorageVersionInfo::GetStorageVersionDefault();
	result.duckdb_version = "latest";
	result.manually_set = false;
	return result;
}

// src/common/storage_compatibility.cpp, except duckdb_version: upstream sets it
// from StorageVersionInfo::GetStorageVersionString (storage_info.cpp, not
// exported). Only CompareVersionString reads it, and the serializer never calls
// that: it decides through Compare(StorageVersion) alone. The quack message
// layer serializes with FromIndex(StorageVersion::V2_0_0).
StorageCompatibility StorageCompatibility::FromIndex(StorageVersion storage_version_p) {
	StorageCompatibility result;
	result.storage_version = storage_version_p;
	result.duckdb_version = "";
	result.manually_set = false;
	return result;
}

// src/common/storage_compatibility.cpp verbatim.
bool StorageCompatibility::Compare(StorageVersion property_version) const {
	return property_version <= storage_version;
}

// Only reached from the serializer's optional-property default-skip path.
// Returning false disables that write-side optimization (every property is
// serialized explicitly), which is wire-correct: the reader always reads the
// explicit value it finds. This avoids compiling comparison_operations.cpp,
// which pulls in the vector / planner / variant machinery.
bool ValueOperations::NotDistinctFrom(const Value &, const Value &) {
	return false;
}

} // namespace duckdb
