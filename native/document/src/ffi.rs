use crate::{codec, store};
use std::collections::HashMap;
use std::ffi::{c_void, OsStr};
use std::fs::File;
use std::io::BufReader;
use std::os::unix::ffi::OsStrExt;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

type Input = codec::Reader<BufReader<File>>;
type ReaderRef = Arc<Mutex<Input>>;
type WriterRef = Arc<Mutex<Option<store::Writer>>>;

static READERS: OnceLock<Mutex<HashMap<u64, ReaderRef>>> = OnceLock::new();
static WRITERS: OnceLock<Mutex<HashMap<u64, WriterRef>>> = OnceLock::new();
static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

fn readers() -> &'static Mutex<HashMap<u64, ReaderRef>> {
    READERS.get_or_init(Mutex::default)
}
fn writers() -> &'static Mutex<HashMap<u64, WriterRef>> {
    WRITERS.get_or_init(Mutex::default)
}
pub(crate) fn handle() -> Option<u64> {
    next_handle(&NEXT_HANDLE)
}

pub(crate) fn next_handle(counter: &AtomicU64) -> Option<u64> {
    let mut current = counter.load(Ordering::Relaxed);
    loop {
        let next = current
            .checked_add(1)
            .filter(|next| *next <= i64::MAX as u64)?;
        match counter.compare_exchange_weak(current, next, Ordering::Relaxed, Ordering::Relaxed) {
            Ok(id) => return Some(id),
            Err(value) => current = value,
        }
    }
}

#[repr(C)]
#[derive(Default, Copy, Clone)]
pub struct Header {
    pub tool: i32,
    pub color: i32,
    pub size_bits: u32,
    pub epsilon_bits: u32,
    pub layer: i32,
    pub nib: i32,
}

/// Only the C++ JNI bridge owns these tokens. No address is exposed to Java.
#[repr(C)]
pub struct Buffer {
    pub data: *const u8,
    pub len: usize,
    pub owner: *mut c_void,
}

fn protect(call: impl FnOnce() -> i32) -> i32 {
    catch_unwind(AssertUnwindSafe(call)).unwrap_or(-9)
}

unsafe fn bytes<'a>(data: *const u8, len: usize) -> Option<&'a [u8]> {
    if len == 0 {
        return Some(&[]);
    }
    if data.is_null() || len > codec::MAX_INPUT_BYTES {
        return None;
    }
    // SAFETY: the C++ caller lends an initialized, live buffer for this call.
    Some(unsafe { std::slice::from_raw_parts(data, len) })
}

#[no_mangle]
pub extern "C" fn notesis_document_abi() -> i32 {
    1
}

#[no_mangle]
pub unsafe extern "C" fn notesis_reader_open(path: *const u8, len: usize) -> u64 {
    catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: only borrowed for this call; paths are copied by File::open.
        let Some(path) = (unsafe { bytes(path, len) }) else {
            return 0;
        };
        if path.is_empty() || path.len() > 65536 {
            return 0;
        }
        let Ok(reader) = store::read_page(Path::new(OsStr::from_bytes(path))) else {
            return 0;
        };
        let Some(id) = handle() else { return 0 };
        readers()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(id, Arc::new(Mutex::new(reader)));
        id
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn notesis_reader_info(id: u64, output: *mut i32) -> i32 {
    protect(|| {
        if output.is_null() {
            return -1;
        }
        let reader = readers()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&id)
            .cloned();
        let Some(reader) = reader else { return -3 };
        let reader = reader.lock().unwrap_or_else(|e| e.into_inner());
        // SAFETY: the bridge supplies two writable i32 slots.
        unsafe {
            output.write(reader.version as i32);
            output.add(1).write(reader.count as i32);
        }
        0
    })
}

#[no_mangle]
pub unsafe extern "C" fn notesis_reader_next(
    id: u64,
    header: *mut Header,
    output: *mut Buffer,
) -> i32 {
    protect(|| {
        if header.is_null() || output.is_null() {
            return -1;
        }
        let reader = readers()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&id)
            .cloned();
        let Some(reader) = reader else { return -3 };
        // Registry locks are released before disk work. Closing an in-flight
        // reader drops its registry entry, but its Arc keeps this call valid.
        let mut reader = reader.lock().unwrap_or_else(|e| e.into_inner());
        match reader.next_record() {
            Ok(None) => 0,
            Err(error) if error.kind() == std::io::ErrorKind::Unsupported => -4,
            Err(_) => -2,
            Ok(Some(stroke)) => {
                let meta = Header {
                    tool: stroke.tool,
                    color: stroke.color,
                    size_bits: stroke.size_bits,
                    epsilon_bits: stroke.epsilon_bits,
                    layer: i32::from(stroke.layer),
                    nib: i32::from(stroke.nib),
                };
                let owned = Box::new(stroke.inputs);
                let result = Buffer {
                    data: owned.as_ptr(),
                    len: owned.len(),
                    owner: Box::into_raw(owned).cast(),
                };
                // SAFETY: writable bridge-owned structs, no Java-visible pointer.
                unsafe {
                    header.write(meta);
                    output.write(result);
                }
                1
            }
        }
    })
}

#[no_mangle]
pub extern "C" fn notesis_reader_close(id: u64) {
    let _ = catch_unwind(|| {
        readers()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&id);
    });
}

#[no_mangle]
pub unsafe extern "C" fn notesis_buffer_free(buffer: *mut Buffer) {
    if buffer.is_null() {
        return;
    }
    // SAFETY: the bridge returns the owner token exactly once after copying it.
    let value = unsafe { &mut *buffer };
    if !value.owner.is_null() {
        // SAFETY: only notesis_reader_next creates this Box<Vec<u8>> token.
        drop(unsafe { Box::from_raw(value.owner.cast::<Vec<u8>>()) });
        value.owner = std::ptr::null_mut();
        value.data = std::ptr::null();
        value.len = 0;
    }
}

#[no_mangle]
pub unsafe extern "C" fn notesis_writer_open(path: *const u8, len: usize, count: u32) -> u64 {
    catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: the JNI bridge owns the lent path bytes until this call returns.
        let Some(path) = (unsafe { bytes(path, len) }) else {
            return 0;
        };
        if path.is_empty() || path.len() > 65536 {
            return 0;
        }
        let Ok(writer) = store::PageWriter::create(Path::new(OsStr::from_bytes(path)), count)
        else {
            return 0;
        };
        let Some(id) = handle() else { return 0 };
        writers().lock().unwrap_or_else(|e| e.into_inner()).insert(
            id,
            Arc::new(Mutex::new(Some(store::Writer::Replace(writer)))),
        );
        id
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn notesis_appender_open(
    path: *const u8,
    len: usize,
    old: u32,
    count: u32,
) -> u64 {
    catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: path bytes are lent by the bridge until File::open completes.
        let Some(path) = (unsafe { bytes(path, len) }) else {
            return 0;
        };
        if path.is_empty() || path.len() > 65536 {
            return 0;
        }
        let Ok(writer) = store::PageAppender::open(Path::new(OsStr::from_bytes(path)), old, count)
        else {
            return 0;
        };
        let Some(id) = handle() else { return 0 };
        writers().lock().unwrap_or_else(|e| e.into_inner()).insert(
            id,
            Arc::new(Mutex::new(Some(store::Writer::Append(writer)))),
        );
        id
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn notesis_writer_push(
    id: u64,
    header: *const Header,
    input: *const u8,
    len: usize,
) -> i32 {
    protect(|| {
        if header.is_null() {
            return -1;
        }
        // SAFETY: the bridge owns a valid header and lent input bytes.
        let meta = unsafe { *header };
        // SAFETY: bytes are lent until this synchronous write completes.
        let Some(input) = (unsafe { bytes(input, len) }) else {
            return -1;
        };
        if !(0..=255).contains(&meta.layer) || !(-128..=127).contains(&meta.nib) {
            return -1;
        }
        let writer = writers()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&id)
            .cloned();
        let Some(writer) = writer else { return -3 };
        let mut writer = writer.lock().unwrap_or_else(|e| e.into_inner());
        let Some(writer) = writer.as_mut() else {
            return -3;
        };
        // Borrow the JNI bridge's scratch bytes only for the synchronous write.
        let stroke = codec::StrokeView {
            tool: meta.tool,
            color: meta.color,
            size_bits: meta.size_bits,
            epsilon_bits: meta.epsilon_bits,
            layer: meta.layer as u8,
            nib: meta.nib as i8,
            inputs: input,
        };
        match writer.push_view(&stroke) {
            Ok(()) => 0,
            Err(error) if error.kind() == std::io::ErrorKind::Unsupported => -4,
            Err(_) => -2,
        }
    })
}

#[no_mangle]
pub extern "C" fn notesis_writer_commit(id: u64) -> i32 {
    protect(|| {
        let writer = writers()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&id);
        let Some(writer) = writer else { return -3 };
        let owned = writer.lock().unwrap_or_else(|e| e.into_inner()).take();
        let Some(owned) = owned else { return -3 };
        match owned.commit() {
            Ok(()) => 0,
            Err(_) => -2,
        }
    })
}

#[no_mangle]
pub extern "C" fn notesis_writer_abort(id: u64) {
    let _ = catch_unwind(|| {
        writers()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&id);
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn unknown_handles_and_null_outputs_are_rejected_without_dereferencing() {
        let mut info = [0; 2];
        // SAFETY: the supplied array has two writable slots.
        let status = unsafe { notesis_reader_info(u64::MAX, info.as_mut_ptr()) };
        assert_eq!(status, -3);
        // SAFETY: null is deliberately rejected by these APIs before access.
        assert_eq!(unsafe { notesis_reader_info(0, std::ptr::null_mut()) }, -1);
        assert_eq!(notesis_writer_commit(0), -3);
        notesis_reader_close(0);
        notesis_writer_abort(0);
    }
}
