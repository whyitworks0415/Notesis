# Native document engine

This crate owns the NTSS byte protocol, streaming file cursors, full-save
transactions, incremental append transactions and Markdown text history.
It uses the Rust standard library only. Android and AndroidX Ink are outside
the crate; the C++ JNI bridge supplies opaque ownership IDs and copies bounded
payloads into the JVM where Ink's public APIs require them.

The current application still has its Kotlin document/page objects, ink event
controller and Compose screens. This is a backend migration, not a mostly-native
application yet. Adding Rust code or a native dependency does not establish that
the old Kotlin implementation has been replaced.

## Ownership and compatibility

- Reads support NTSS versions 1 through 4. Writes remain version 4.
- Tool/tag bits, ARGB, floating-point bits, layer and signed nib are preserved.
- A complete prefix can still be read when the last record is truncated.
- Actual file bounds are checked before allocating a claimed input payload.
- Large valid inputs outside native budgets use the compatibility path.
- Full replacements flush and sync before renaming over the original.
- Appends commit the count after their data; abandoning one rolls back its
  uncommitted suffix. A later append scans the committed prefix and discards
  a tail left by a process that did not complete its previous append.
- Append uses the existing v4 count-last protocol. It is not a new journaled
  file format, and the tests do not establish arbitrary power-loss durability.
- Registry IDs are unique across resource kinds and are never native addresses.
  Per-resource locks protect the cursor; no registry-wide lock spans file I/O.
- Pending writers are aborted on close. Readers and Markdown history are closed
  by their Kotlin owner, including Compose disposal.
- Text snapshots retain UTF-16 code units and selections exactly. A merged typing
  event does not request a full text snapshot from JNI.

## Build

Rust 1.100.0 is used in CI. For the tablet, install its
`aarch64-linux-android` standard library; `x86_64-linux-android` is used for an
optional emulator build. CMake invokes Cargo with `--release --locked` and links
the static archive into `libnotesis_core.so`.

Run `cargo test --manifest-path native/document/Cargo.toml --locked` and
`cargo clippy --manifest-path native/document/Cargo.toml --all-targets -- -D warnings`.
`tools/performance-lab/check-native.sh` also builds the host JNI bridge for the
JVM regression tests. `RustDocumentTest` tests the real bridge against legacy
bytes, the C++ index, a text history reference and concurrent file cursors.

The native engine switch requires a full app restart for a fair comparison:
already-open documents have caches and a text history keeps the backend it was
created with. All timing and battery claims still require device measurements.
