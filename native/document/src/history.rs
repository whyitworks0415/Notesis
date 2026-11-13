use std::collections::VecDeque;

/// Text is UTF-16: Android selection offsets count code units, not UTF-8 bytes
/// or Unicode scalar values. Korean composition and emoji must round-trip.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TextState {
    pub text: Vec<u16>,
    pub start: i32,
    pub end: i32,
}

pub struct TextHistory {
    limit: usize,
    back: VecDeque<TextState>,
    forward: Vec<TextState>,
    last_at: i64,
    last_grew: Option<bool>,
}

impl TextHistory {
    pub fn new(limit: usize) -> Self {
        Self {
            limit,
            back: VecDeque::new(),
            forward: Vec::new(),
            last_at: 0,
            last_grew: None,
        }
    }
    pub fn can_undo(&self) -> bool {
        !self.back.is_empty()
    }
    pub fn can_redo(&self) -> bool {
        !self.forward.is_empty()
    }
    pub fn record(&mut self, before: TextState, after: &TextState, typed: bool, now: i64) {
        if before.text == after.text {
            return;
        }
        let before_len = before.text.len();
        let _ = self.record_change(before_len, after.text.len(), typed, now, || {
            Ok::<_, ()>(before)
        });
    }

    /// JNI callers only materialize text when a new undo group needs it.
    /// Continuous typing does not copy the whole document on each keystroke.
    pub fn record_change<E>(
        &mut self,
        before_len: usize,
        after_len: usize,
        typed: bool,
        now: i64,
        snapshot: impl FnOnce() -> Result<TextState, E>,
    ) -> Result<(), E> {
        let grew = after_len > before_len;
        let joins = typed
            && self.last_grew == Some(grew)
            && now.wrapping_sub(self.last_at) < 1000
            && !self.back.is_empty();
        if !joins {
            self.back.push_back(snapshot()?);
            while self.back.len() > self.limit {
                self.back.pop_front();
            }
        }
        self.forward.clear();
        self.last_at = now;
        self.last_grew = if typed { Some(grew) } else { None };
        Ok(())
    }
    pub fn undo(&mut self, current: TextState) -> Option<TextState> {
        let state = self.back.pop_back()?;
        self.forward.push(current);
        self.last_grew = None;
        Some(state)
    }
    pub fn redo(&mut self, current: TextState) -> Option<TextState> {
        let state = self.forward.pop()?;
        self.back.push_back(current);
        self.last_grew = None;
        Some(state)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn state(text: &str) -> TextState {
        let text: Vec<_> = text.encode_utf16().collect();
        let end = text.len() as i32;
        TextState {
            text,
            start: end,
            end,
        }
    }
    #[test]
    fn typing_groups_and_unicode_selection_survive_undo_redo() {
        let mut history = TextHistory::new(200);
        let a = state("한");
        let b = state("한글");
        let c = state("한글📝");
        history.record(a.clone(), &b, true, 100);
        history.record(b, &c, true, 200);
        assert_eq!(history.undo(c.clone()), Some(a.clone()));
        assert!(!history.can_undo());
        assert!(history.can_redo());
        assert_eq!(history.redo(a), Some(c));
    }
    #[test]
    fn explicit_edits_timeout_direction_changes_and_limits_split_groups() {
        let mut history = TextHistory::new(2);
        let values = [state(""), state("a"), state("ab"), state("abc")];
        for i in 0..3 {
            history.record(values[i].clone(), &values[i + 1], false, i as i64);
        }
        assert_eq!(history.undo(values[3].clone()), Some(values[2].clone()));
        assert_eq!(history.undo(values[2].clone()), Some(values[1].clone()));
        assert_eq!(history.undo(values[1].clone()), None);
        history.record(values[1].clone(), &values[0], true, 3000);
        assert!(!history.can_redo());
    }
}
