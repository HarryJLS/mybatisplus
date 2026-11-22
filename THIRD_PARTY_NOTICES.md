# Third-party notices

The independent plugin uses these libraries. The supplied original plugin is not a build dependency.

| Library | Version | License |
| --- | --- | --- |
| MyBatis | 3.5.19 | Apache-2.0 |
| Gson | 2.13.2 | Apache-2.0 |
| Gradle (build tool) | 9.2.1 | Apache-2.0 |
| Gradle wrapper bootstrap | 8.14.3 | Apache-2.0 |
| H2 (test only, not bundled) | 2.3.232 | MPL-2.0 / EPL-1.0 |
| bytes (Rust dependency) | 1.12.1 | MIT |
| cesu8 (Rust dependency) | 1.1.0 | Apache-2.0/MIT |
| cfg-if (Rust dependency) | 1.0.5 | MIT OR Apache-2.0 |
| combine (Rust dependency) | 4.6.9 | MIT |
| itoa (Rust dependency) | 1.0.19 | MIT OR Apache-2.0 |
| jni (Rust dependency) | 0.21.1 | MIT/Apache-2.0 |
| jni-sys (Rust dependency) | 0.3.1 | MIT OR Apache-2.0 |
| jni-sys (Rust dependency) | 0.4.1 | MIT OR Apache-2.0 |
| jni-sys-macros (Rust dependency) | 0.4.1 | MIT OR Apache-2.0 |
| log (Rust dependency) | 0.4.35 | MIT OR Apache-2.0 |
| memchr (Rust dependency) | 2.8.3 | Unlicense OR MIT |
| proc-macro2 (Rust dependency) | 1.0.107 | MIT OR Apache-2.0 |
| quote (Rust dependency) | 1.0.47 | MIT OR Apache-2.0 |
| same-file (Rust dependency) | 1.0.6 | Unlicense/MIT |
| serde (Rust dependency) | 1.0.229 | MIT OR Apache-2.0 |
| serde_core (Rust dependency) | 1.0.229 | MIT OR Apache-2.0 |
| serde_derive (Rust dependency) | 1.0.229 | MIT OR Apache-2.0 |
| serde_json (Rust dependency) | 1.0.154 | MIT OR Apache-2.0 |
| syn (Rust dependency) | 2.0.119 | MIT OR Apache-2.0 |
| syn (Rust dependency) | 3.0.10 | MIT OR Apache-2.0 |
| thiserror (Rust dependency) | 1.0.69 | MIT OR Apache-2.0 |
| thiserror-impl (Rust dependency) | 1.0.69 | MIT OR Apache-2.0 |
| unicode-ident (Rust dependency) | 1.0.27 | (MIT OR Apache-2.0) AND Unicode-3.0 |
| walkdir (Rust dependency) | 2.5.0 | Unlicense/MIT |
| winapi-util (Rust dependency) | 0.1.11 | Unlicense OR MIT |
| windows-link (Rust dependency) | 0.2.1 | MIT OR Apache-2.0 |
| windows-sys (Rust dependency) | 0.45.0 | MIT OR Apache-2.0 |
| windows-sys (Rust dependency) | 0.61.2 | MIT OR Apache-2.0 |
| windows-targets (Rust dependency) | 0.42.2 | MIT OR Apache-2.0 |
| windows_aarch64_gnullvm (Rust dependency) | 0.42.2 | MIT OR Apache-2.0 |
| windows_aarch64_msvc (Rust dependency) | 0.42.2 | MIT OR Apache-2.0 |
| windows_i686_gnu (Rust dependency) | 0.42.2 | MIT OR Apache-2.0 |
| windows_i686_msvc (Rust dependency) | 0.42.2 | MIT OR Apache-2.0 |
| windows_x86_64_gnu (Rust dependency) | 0.42.2 | MIT OR Apache-2.0 |
| windows_x86_64_gnullvm (Rust dependency) | 0.42.2 | MIT OR Apache-2.0 |
| windows_x86_64_msvc (Rust dependency) | 0.42.2 | MIT OR Apache-2.0 |
| zmij (Rust dependency) | 1.0.23 | MIT |

The Cargo lockfile also includes build and non-host platform dependencies; not every listed package is linked into each native binary. MyBatis and Gson carry their license metadata in the bundled JARs. The JNI engine is built from the Rust source in this repository.

`PreviewForEachSqlNode` derives its foreach parameter/prefix handling from MyBatis 3.5.19 `ForEachSqlNode` (Copyright 2009–2025 the original authors, Apache-2.0). It uses request-owned expressions and cooperative cancellation; the upstream copyright and license notice is retained in the source.
