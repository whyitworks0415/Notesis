use crate::codec::{self, Reader, Stroke};
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufReader, BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

static NEXT_TEMP: AtomicU64 = AtomicU64::new(1);

pub fn read_page(path: &Path) -> io::Result<Reader<BufReader<File>>> {
    let mut reader = Reader::open(BufReader::new(File::open(path)?))?;
    // Measure the same inode after reading its committed count. A count that
    // just advanced can only refer to bytes already appended before it.
    let length = reader.source().get_ref().metadata()?.len();
    reader.bound_to_file(length);
    Ok(reader)
}

/// A pending replacement is private until every expected record is durable.
/// Dropping, cancellation and errors only remove its temporary file.
pub struct PageWriter {
    destination: PathBuf,
    temporary: PathBuf,
    output: Option<BufWriter<File>>,
    expected: u32,
    written: u32,
    failed: bool,
    bytes: u64,
}

impl PageWriter {
    pub fn create(destination: &Path, expected: u32) -> io::Result<Self> {
        if expected > codec::MAX_RECORDS {
            return Err(io::Error::other("Too many strokes"));
        }
        let parent = destination
            .parent()
            .ok_or_else(|| io::Error::other("Missing page directory"))?;
        let name = destination
            .file_name()
            .ok_or_else(|| io::Error::other("Missing page name"))?;
        let (temporary, file) = loop {
            let id = NEXT_TEMP.fetch_add(1, Ordering::Relaxed);
            let mut name = name.to_os_string();
            name.push(format!(".native-{}-{id}.tmp", std::process::id()));
            let path = parent.join(name);
            match OpenOptions::new().write(true).create_new(true).open(&path) {
                Ok(file) => break (path, file),
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
                Err(error) => return Err(error),
            }
        };
        let mut writer = Self {
            destination: destination.to_owned(),
            temporary,
            output: Some(BufWriter::new(file)),
            expected,
            written: 0,
            failed: false,
            bytes: codec::HEADER_BYTES,
        };
        codec::write_header(writer.output.as_mut().unwrap(), expected)?;
        Ok(writer)
    }

    pub fn push(&mut self, stroke: &Stroke) -> io::Result<()> {
        self.push_view(&stroke.into())
    }

    pub fn push_view(&mut self, stroke: &codec::StrokeView<'_>) -> io::Result<()> {
        if self.failed || self.written >= self.expected {
            return Err(io::Error::other("Writer is not accepting records"));
        }
        let end = self
            .bytes
            .checked_add(22 + stroke.inputs.len() as u64)
            .filter(|end| *end <= codec::MAX_PAGE_BYTES)
            .ok_or_else(|| {
                io::Error::new(io::ErrorKind::Unsupported, "Page exceeds the write budget")
            })?;
        match codec::write_view(self.output.as_mut().unwrap(), stroke) {
            Ok(()) => {
                self.written += 1;
                self.bytes = end;
                Ok(())
            }
            Err(error) => {
                self.failed = true;
                Err(error)
            }
        }
    }

    /// Rename is the commit point. No error after it can trigger a stale retry.
    pub fn commit(mut self) -> io::Result<()> {
        if self.failed || self.written != self.expected {
            return Err(io::Error::other("Incomplete page replacement"));
        }
        let mut output = self.output.take().unwrap();
        output.flush()?;
        output.get_ref().sync_all()?;
        drop(output);
        fs::rename(&self.temporary, &self.destination)?;
        // Android filesystems can refuse directory fsync. The replacement has
        // already happened, so it must not be reported as an uncommitted save.
        if let Some(parent) = self.destination.parent() {
            if let Ok(directory) = File::open(parent) {
                let _ = directory.sync_all();
            }
        }
        Ok(())
    }
}

impl Drop for PageWriter {
    fn drop(&mut self) {
        drop(self.output.take());
        let _ = fs::remove_file(&self.temporary);
    }
}

/// The v4 append protocol commits its count last. Before another append, locate
/// the committed prefix so a tail left by a killed process cannot become ink.
pub struct PageAppender {
    file: File,
    output: Option<BufWriter<File>>,
    old_count: u32,
    expected: u32,
    written: u32,
    prefix: u64,
    bytes: u64,
    committed: bool,
    failed: bool,
}

impl PageAppender {
    pub fn open(path: &Path, old_count: u32, expected: u32) -> io::Result<Self> {
        if old_count == 0 || expected <= old_count || expected > codec::MAX_RECORDS {
            return Err(io::Error::other("Invalid append count"));
        }
        let file = OpenOptions::new().read(true).write(true).open(path)?;
        let length = file.metadata()?.len();
        let mut reader = BufReader::new(file.try_clone()?);
        let mut header = [0_u8; 12];
        reader.read_exact(&mut header)?;
        if u32::from_be_bytes(header[0..4].try_into().unwrap()) != codec::MAGIC
            || u32::from_be_bytes(header[4..8].try_into().unwrap()) != codec::VERSION
            || u32::from_be_bytes(header[8..12].try_into().unwrap()) != old_count
        {
            return Err(io::Error::other("The saved page changed"));
        }
        let mut prefix = codec::HEADER_BYTES;
        for _ in 0..old_count {
            let mut record = [0_u8; 20];
            reader.read_exact(&mut record)?;
            let payload = u64::from(u32::from_be_bytes(record[16..20].try_into().unwrap()));
            prefix = prefix
                .checked_add(22 + payload)
                .filter(|end| *end <= length)
                .ok_or_else(|| {
                    io::Error::new(io::ErrorKind::UnexpectedEof, "Incomplete saved stroke")
                })?;
            reader.seek_relative((payload + 2) as i64)?;
        }
        drop(reader);
        let mut file = file;
        file.set_len(prefix)?;
        file.seek(SeekFrom::Start(prefix))?;
        let output = BufWriter::new(file.try_clone()?);
        Ok(Self {
            file,
            output: Some(output),
            old_count,
            expected: expected - old_count,
            written: 0,
            prefix,
            bytes: prefix,
            committed: false,
            failed: false,
        })
    }

    pub fn push_view(&mut self, stroke: &codec::StrokeView<'_>) -> io::Result<()> {
        if self.failed || self.written >= self.expected {
            return Err(io::Error::other("Invalid append state"));
        }
        let end = self
            .bytes
            .checked_add(22 + stroke.inputs.len() as u64)
            .filter(|end| *end <= codec::MAX_PAGE_BYTES)
            .ok_or_else(|| {
                io::Error::new(io::ErrorKind::Unsupported, "Page exceeds the append budget")
            })?;
        match codec::write_view(self.output.as_mut().unwrap(), stroke) {
            Ok(()) => {
                self.written += 1;
                self.bytes = end;
                Ok(())
            }
            Err(error) => {
                self.failed = true;
                Err(error)
            }
        }
    }

    pub fn commit(mut self) -> io::Result<()> {
        if self.failed || self.written != self.expected {
            return Err(io::Error::other("Incomplete append"));
        }
        let mut output = self.output.take().unwrap();
        output.flush()?;
        drop(output);
        self.file.sync_data()?;
        self.file.seek(SeekFrom::Start(8))?;
        self.file
            .write_all(&(self.old_count + self.expected).to_be_bytes())?;
        self.file.sync_data()?;
        self.committed = true;
        Ok(())
    }
}

impl Drop for PageAppender {
    fn drop(&mut self) {
        if let Some(output) = self.output.take() {
            // Drop must discard pending data, not flush it after rollback.
            let (file, _) = output.into_parts();
            drop(file);
        }
        if !self.committed {
            let _ = self.file.seek(SeekFrom::Start(8));
            let _ = self.file.write_all(&self.old_count.to_be_bytes());
            let _ = self.file.set_len(self.prefix);
            let _ = self.file.sync_data();
        }
    }
}

pub enum Writer {
    Replace(PageWriter),
    Append(PageAppender),
}
impl Writer {
    pub fn push_view(&mut self, stroke: &codec::StrokeView<'_>) -> io::Result<()> {
        match self {
            Self::Replace(writer) => writer.push_view(stroke),
            Self::Append(writer) => writer.push_view(stroke),
        }
    }
    pub fn commit(self) -> io::Result<()> {
        match self {
            Self::Replace(writer) => writer.commit(),
            Self::Append(writer) => writer.commit(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn folder() -> PathBuf {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path =
            std::env::temp_dir().join(format!("notesis-문서-📝-{}-{stamp}", std::process::id()));
        fs::create_dir(&path).unwrap();
        path
    }

    fn stroke() -> Stroke {
        Stroke {
            tool: 1,
            color: -1,
            size_bits: 1_f32.to_bits(),
            epsilon_bits: 0.01_f32.to_bits(),
            inputs: vec![9; 100],
            layer: 2,
            nib: -128,
        }
    }

    #[test]
    fn aborted_and_incomplete_writes_preserve_the_previous_file() {
        let dir = folder();
        let path = dir.join("page.bin");
        fs::write(&path, b"previous save").unwrap();
        {
            let mut writer = PageWriter::create(&path, 2).unwrap();
            writer.push(&stroke()).unwrap();
        }
        assert_eq!(fs::read(&path).unwrap(), b"previous save");
        let mut writer = PageWriter::create(&path, 2).unwrap();
        writer.push(&stroke()).unwrap();
        assert!(writer.commit().is_err());
        assert_eq!(fs::read(&path).unwrap(), b"previous save");
        assert_eq!(fs::read_dir(&dir).unwrap().count(), 1);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn complete_replacement_round_trips_and_cleans_up_temporary_files() {
        let dir = folder();
        let path = dir.join("page.bin");
        let mut writer = PageWriter::create(&path, 100).unwrap();
        for _ in 0..100 {
            writer.push(&stroke()).unwrap();
        }
        writer.commit().unwrap();
        let mut reader = read_page(&path).unwrap();
        assert_eq!(reader.count, 100);
        for _ in 0..100 {
            assert_eq!(reader.next_record().unwrap(), Some(stroke()));
        }
        assert_eq!(reader.next_record().unwrap(), None);
        assert_eq!(fs::read_dir(&dir).unwrap().count(), 1);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn append_recovers_an_abandoned_tail_and_does_not_duplicate_it() {
        let dir = folder();
        let path = dir.join("page.bin");
        let mut writer = PageWriter::create(&path, 1).unwrap();
        writer.push(&stroke()).unwrap();
        writer.commit().unwrap();
        let before = fs::read(&path).unwrap();
        OpenOptions::new()
            .append(true)
            .open(&path)
            .unwrap()
            .write_all(b"abandoned append")
            .unwrap();
        let mut writer = PageAppender::open(&path, 1, 2).unwrap();
        writer.push_view(&(&stroke()).into()).unwrap();
        writer.commit().unwrap();
        let mut reader = read_page(&path).unwrap();
        assert_eq!(reader.count, 2);
        assert_eq!(reader.next_record().unwrap(), Some(stroke()));
        assert_eq!(reader.next_record().unwrap(), Some(stroke()));
        assert_eq!(reader.next_record().unwrap(), None);
        let mut expected = before.clone();
        expected[8..12].copy_from_slice(&2_u32.to_be_bytes());
        codec::write_record(&mut expected, &stroke()).unwrap();
        assert_eq!(fs::read(&path).unwrap(), expected);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn aborted_append_preserves_the_committed_bytes() {
        let dir = folder();
        let path = dir.join("page.bin");
        let mut writer = PageWriter::create(&path, 1).unwrap();
        writer.push(&stroke()).unwrap();
        writer.commit().unwrap();
        let before = fs::read(&path).unwrap();
        {
            let mut writer = PageAppender::open(&path, 1, 3).unwrap();
            writer.push_view(&(&stroke()).into()).unwrap();
        }
        assert_eq!(fs::read(&path).unwrap(), before);
        assert!(PageAppender::open(&path, 2, 3).is_err());
        assert_eq!(fs::read(&path).unwrap(), before);
        fs::remove_dir_all(dir).unwrap();
    }
}
