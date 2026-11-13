use crate::history::{TextHistory, TextState};
use std::collections::HashMap;
use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, Mutex, OnceLock};

type HistoryRef = Arc<Mutex<TextHistory>>;
static HISTORIES: OnceLock<Mutex<HashMap<u64, HistoryRef>>> = OnceLock::new();
fn histories() -> &'static Mutex<HashMap<u64, HistoryRef>> {
    HISTORIES.get_or_init(Mutex::default)
}
fn get(id: u64) -> Option<HistoryRef> {
    histories()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&id)
        .cloned()
}
fn flags(history: &TextHistory) -> i32 {
    i32::from(history.can_undo()) | (i32::from(history.can_redo()) << 1)
}
fn protect(call: impl FnOnce() -> i32) -> i32 {
    catch_unwind(AssertUnwindSafe(call)).unwrap_or(-9)
}

#[repr(C)]
pub struct TextView {
    pub data: *const u16,
    pub len: usize,
    pub start: i32,
    pub end: i32,
}
#[repr(C)]
pub struct OwnedText {
    pub value: TextView,
    pub owner: *mut c_void,
    pub flags: i32,
}
pub type Snapshot = extern "C" fn(*mut c_void, *mut TextView) -> i32;

fn snapshot(callback: Snapshot, context: *mut c_void) -> Result<TextState, i32> {
    let mut view = TextView {
        data: std::ptr::null(),
        len: 0,
        start: 0,
        end: 0,
    };
    if callback(context, &mut view) != 0 || view.len > i32::MAX as usize {
        return Err(-1);
    }
    let mut text = Vec::new();
    text.try_reserve_exact(view.len).map_err(|_| -4)?;
    if view.len > 0 {
        if view.data.is_null() || (view.data as usize) % std::mem::align_of::<u16>() != 0 {
            return Err(-1);
        }
        // SAFETY: the synchronous bridge callback lends these UTF-16 code units
        // until the enclosing FFI call returns. No pointer is retained.
        text.extend_from_slice(unsafe { std::slice::from_raw_parts(view.data, view.len) });
    }
    Ok(TextState {
        text,
        start: view.start,
        end: view.end,
    })
}

#[no_mangle]
pub extern "C" fn notesis_history_create(limit: u32) -> u64 {
    if limit > 10000 {
        return 0;
    }
    catch_unwind(|| {
        let Some(id) = crate::ffi::handle() else {
            return 0;
        };
        histories()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(id, Arc::new(Mutex::new(TextHistory::new(limit as usize))));
        id
    })
    .unwrap_or(0)
}

#[no_mangle]
pub extern "C" fn notesis_history_record(
    id: u64,
    before_len: usize,
    after_len: usize,
    typed: bool,
    now: i64,
    callback: Snapshot,
    context: *mut c_void,
) -> i32 {
    protect(|| {
        if before_len > i32::MAX as usize || after_len > i32::MAX as usize {
            return -1;
        }
        let Some(history) = get(id) else { return -3 };
        let mut history = history.lock().unwrap_or_else(|e| e.into_inner());
        match history.record_change(before_len, after_len, typed, now, || {
            snapshot(callback, context)
        }) {
            Ok(()) => flags(&history),
            Err(error) => error,
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn notesis_history_move(
    id: u64,
    redo: bool,
    callback: Snapshot,
    context: *mut c_void,
    output: *mut OwnedText,
) -> i32 {
    protect(|| {
        if output.is_null() {
            return -1;
        }
        let Some(history) = get(id) else { return -3 };
        let mut history = history.lock().unwrap_or_else(|e| e.into_inner());
        if !(if redo {
            history.can_redo()
        } else {
            history.can_undo()
        }) {
            return 0;
        }
        let current = match snapshot(callback, context) {
            Ok(state) => state,
            Err(error) => return error,
        };
        let state = if redo {
            history.redo(current)
        } else {
            history.undo(current)
        }
        .unwrap();
        let owned = Box::new(state.text);
        let result = OwnedText {
            value: TextView {
                data: owned.as_ptr(),
                len: owned.len(),
                start: state.start,
                end: state.end,
            },
            owner: Box::into_raw(owned).cast(),
            flags: flags(&history),
        };
        // SAFETY: the JNI bridge supplies a writable output slot and frees its token once.
        unsafe {
            output.write(result);
        }
        1
    })
}

#[no_mangle]
pub unsafe extern "C" fn notesis_text_free(output: *mut OwnedText) {
    if output.is_null() {
        return;
    }
    // SAFETY: only a token from notesis_history_move is accepted by the bridge.
    let value = unsafe { &mut *output };
    if !value.owner.is_null() {
        // SAFETY: matches the Box<Vec<u16>> allocated above, consumed exactly once.
        drop(unsafe { Box::from_raw(value.owner.cast::<Vec<u16>>()) });
        value.owner = std::ptr::null_mut();
        value.value.data = std::ptr::null();
        value.value.len = 0;
    }
}

#[no_mangle]
pub extern "C" fn notesis_history_close(id: u64) {
    let _ = catch_unwind(|| {
        histories()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&id);
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    extern "C" fn forbidden(_: *mut c_void, _: *mut TextView) -> i32 {
        panic!("Callback must not run");
    }
    #[test]
    fn unknown_history_and_empty_undo_never_call_the_snapshot_callback() {
        assert_eq!(
            notesis_history_record(0, 1, 2, true, 0, forbidden, std::ptr::null_mut()),
            -3
        );
        let id = notesis_history_create(2);
        let mut result = OwnedText {
            value: TextView {
                data: std::ptr::null(),
                len: 0,
                start: 0,
                end: 0,
            },
            owner: std::ptr::null_mut(),
            flags: 0,
        };
        // SAFETY: the result slot is valid, and no callback is needed for an empty history.
        let status = unsafe {
            notesis_history_move(id, false, forbidden, std::ptr::null_mut(), &mut result)
        };
        assert_eq!(status, 0);
        notesis_history_close(id);
        notesis_history_close(id);
    }
}
