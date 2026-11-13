#pragma once
#include <cstddef>
#include <cstdint>

// C ABI shared with native/document/src/ffi.rs. Addresses never reach Java.
extern "C" {
struct DocumentHeader {
    std::int32_t tool, color;
    std::uint32_t size_bits, epsilon_bits;
    std::int32_t layer, nib;
};
struct DocumentBuffer { const std::uint8_t* data; std::size_t len; void* owner; };
int notesis_document_abi();
std::uint64_t notesis_reader_open(const std::uint8_t* path, std::size_t length);
int notesis_reader_info(std::uint64_t reader, std::int32_t* output);
int notesis_reader_next(std::uint64_t reader, DocumentHeader* header, DocumentBuffer* output);
void notesis_reader_close(std::uint64_t reader);
void notesis_buffer_free(DocumentBuffer* buffer);
std::uint64_t notesis_writer_open(const std::uint8_t* path, std::size_t length, std::uint32_t count);
std::uint64_t notesis_appender_open(const std::uint8_t* path, std::size_t length, std::uint32_t old_count, std::uint32_t count);
int notesis_writer_push(std::uint64_t writer, const DocumentHeader* header, const std::uint8_t* input, std::size_t length);
int notesis_writer_commit(std::uint64_t writer);
void notesis_writer_abort(std::uint64_t writer);
struct DocumentTextView { const std::uint16_t* data; std::size_t len; std::int32_t start, end; };
struct DocumentOwnedText { DocumentTextView value; void* owner; std::int32_t flags; };
using DocumentSnapshot = int (*)(void*, DocumentTextView*);
std::uint64_t notesis_history_create(std::uint32_t limit);
int notesis_history_record(std::uint64_t history, std::size_t before, std::size_t after, bool typed,
    std::int64_t now, DocumentSnapshot snapshot, void* context);
int notesis_history_move(std::uint64_t history, bool redo, DocumentSnapshot snapshot, void* context, DocumentOwnedText* output);
void notesis_text_free(DocumentOwnedText* output);
void notesis_history_close(std::uint64_t history);
}
