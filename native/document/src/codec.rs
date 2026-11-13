use std::io::{self, Read, Write};

pub const MAGIC: u32 = 0x4e54_5353;
pub const VERSION: u32 = 4;
pub const HEADER_BYTES: u64 = 12;
pub const MAX_RECORDS: u32 = 1_000_000;
pub const MAX_INPUT_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_PAGE_BYTES: u64 = 512 * 1024 * 1024;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Stroke {
    pub tool: i32,
    pub color: i32,
    // Preserve bits, including old files' non-canonical floating-point values.
    pub size_bits: u32,
    pub epsilon_bits: u32,
    pub inputs: Vec<u8>,
    pub layer: u8,
    pub nib: i8,
}

pub struct StrokeView<'a> {
    pub tool: i32,
    pub color: i32,
    pub size_bits: u32,
    pub epsilon_bits: u32,
    pub inputs: &'a [u8],
    pub layer: u8,
    pub nib: i8,
}

impl<'a> From<&'a Stroke> for StrokeView<'a> {
    fn from(stroke: &'a Stroke) -> Self {
        Self {
            tool: stroke.tool,
            color: stroke.color,
            size_bits: stroke.size_bits,
            epsilon_bits: stroke.epsilon_bits,
            inputs: &stroke.inputs,
            layer: stroke.layer,
            nib: stroke.nib,
        }
    }
}

fn invalid(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

fn word<R: Read>(input: &mut R) -> io::Result<u32> {
    let mut bytes = [0; 4];
    input.read_exact(&mut bytes)?;
    Ok(u32::from_be_bytes(bytes))
}

fn byte<R: Read>(input: &mut R) -> io::Result<u8> {
    let mut bytes = [0];
    input.read_exact(&mut bytes)?;
    Ok(bytes[0])
}

/// A streaming cursor owns progress, with no page-sized staging allocation.
pub struct Reader<R> {
    input: R,
    pub version: u32,
    pub count: u32,
    read: u32,
    bytes: u64,
    failed: bool,
    file_length: Option<u64>,
}

impl<R: Read> Reader<R> {
    pub fn open(mut input: R) -> io::Result<Self> {
        if word(&mut input)? != MAGIC {
            return Err(invalid("Not an NTSS page"));
        }
        let version = word(&mut input)?;
        let count = word(&mut input)?;
        if !(1..=VERSION).contains(&version) || count > MAX_RECORDS {
            return Err(invalid("Unsupported page header"));
        }
        Ok(Self {
            input,
            version,
            count,
            read: 0,
            bytes: HEADER_BYTES,
            failed: false,
            file_length: None,
        })
    }

    pub fn read_count(&self) -> u32 {
        self.read
    }
    pub fn committed_bytes(&self) -> u64 {
        self.bytes
    }
    pub(crate) fn source(&self) -> &R {
        &self.input
    }
    pub(crate) fn bound_to_file(&mut self, length: u64) {
        self.file_length = Some(length);
    }

    /// The caller may keep the successful prefix after a truncated last record,
    /// matching Notesis' existing recovery behaviour. A failed cursor never resumes.
    pub fn next_record(&mut self) -> io::Result<Option<Stroke>> {
        if self.failed {
            return Err(invalid("Reader already failed"));
        }
        if self.read == self.count {
            return Ok(None);
        }
        let result = self.read_record();
        if result.is_err() {
            self.failed = true;
        }
        result.map(Some)
    }

    fn read_record(&mut self) -> io::Result<Stroke> {
        let tool = word(&mut self.input)? as i32;
        let color = word(&mut self.input)? as i32;
        let size_bits = word(&mut self.input)?;
        let epsilon_bits = word(&mut self.input)?;
        let length = word(&mut self.input)?;
        if length > i32::MAX as u32 {
            return Err(invalid("Negative saved input length"));
        }
        let length = length as usize;
        let trailer = u64::from(self.version >= 3) + u64::from(self.version >= 4);
        let end = self
            .bytes
            .checked_add(20 + length as u64 + trailer)
            .ok_or_else(|| invalid("Invalid input extent"))?;
        if self.file_length.is_some_and(|length| end > length) {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "Truncated stroke payload",
            ));
        }
        if end > MAX_PAGE_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "Page exceeds the native read budget",
            ));
        }
        if length > MAX_INPUT_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "Stroke input exceeds the read budget",
            ));
        }
        let mut inputs = Vec::new();
        inputs
            .try_reserve_exact(length)
            .map_err(|_| io::Error::other("Input allocation failed"))?;
        inputs.resize(length, 0);
        self.input.read_exact(&mut inputs)?;
        let layer = if self.version >= 3 {
            byte(&mut self.input)?
        } else {
            0
        };
        let nib = if self.version >= 4 {
            byte(&mut self.input)? as i8
        } else {
            -128
        };
        self.read += 1;
        self.bytes = end;
        Ok(Stroke {
            tool,
            color,
            size_bits,
            epsilon_bits,
            inputs,
            layer,
            nib,
        })
    }
}

pub fn write_header<W: Write>(output: &mut W, count: u32) -> io::Result<()> {
    if count > MAX_RECORDS {
        return Err(invalid("Too many strokes"));
    }
    output.write_all(&MAGIC.to_be_bytes())?;
    output.write_all(&VERSION.to_be_bytes())?;
    output.write_all(&count.to_be_bytes())
}

pub fn write_record<W: Write>(output: &mut W, stroke: &Stroke) -> io::Result<()> {
    write_view(output, &stroke.into())
}

pub fn write_view<W: Write>(output: &mut W, stroke: &StrokeView<'_>) -> io::Result<()> {
    if stroke.inputs.len() > MAX_INPUT_BYTES {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "Stroke input exceeds the write budget",
        ));
    }
    for bits in [
        stroke.tool as u32,
        stroke.color as u32,
        stroke.size_bits,
        stroke.epsilon_bits,
        stroke.inputs.len() as u32,
    ] {
        output.write_all(&bits.to_be_bytes())?;
    }
    output.write_all(stroke.inputs)?;
    output.write_all(&[stroke.layer, stroke.nib as u8])
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    pub fn sample(index: i32) -> Stroke {
        Stroke {
            tool: 0x100 | index,
            color: 0xff102030_u32 as i32,
            size_bits: 2.5_f32.to_bits(),
            epsilon_bits: 0.01_f32.to_bits(),
            inputs: vec![7, 8, 9],
            layer: 4,
            nib: -128,
        }
    }

    fn old_page(version: u32, count: u32) -> Vec<u8> {
        let mut bytes = Vec::new();
        for word in [MAGIC, version, count] {
            bytes.extend(word.to_be_bytes());
        }
        for i in 0..count {
            let stroke = sample(i as i32);
            for bits in [
                stroke.tool as u32,
                stroke.color as u32,
                stroke.size_bits,
                stroke.epsilon_bits,
                3,
            ] {
                bytes.extend(bits.to_be_bytes());
            }
            bytes.extend(stroke.inputs);
            if version >= 3 {
                bytes.push(stroke.layer);
            }
            if version >= 4 {
                bytes.push(stroke.nib as u8);
            }
        }
        bytes
    }

    #[test]
    fn reads_all_legacy_versions_without_changing_metadata_or_order() {
        for version in 1..=4 {
            let bytes = old_page(version, 200);
            let mut reader = Reader::open(Cursor::new(bytes.clone())).unwrap();
            for i in 0..200 {
                let mut expected = sample(i);
                if version < 3 {
                    expected.layer = 0;
                }
                if version < 4 {
                    expected.nib = -128;
                }
                assert_eq!(reader.next_record().unwrap(), Some(expected));
            }
            assert_eq!(reader.next_record().unwrap(), None);
            assert_eq!(reader.committed_bytes(), bytes.len() as u64);
        }
    }

    #[test]
    fn version_four_round_trip_is_byte_identical() {
        let original = old_page(4, 64);
        let mut reader = Reader::open(Cursor::new(&original)).unwrap();
        let mut output = Vec::new();
        write_header(&mut output, reader.count).unwrap();
        while let Some(stroke) = reader.next_record().unwrap() {
            write_record(&mut output, &stroke).unwrap();
        }
        assert_eq!(output, original);
    }

    #[test]
    fn every_truncation_fails_safely_and_never_resumes() {
        for version in 1..=4 {
            let bytes = old_page(version, 3);
            for end in 0..bytes.len() {
                let Ok(mut reader) = Reader::open(Cursor::new(&bytes[..end])) else {
                    continue;
                };
                let mut kept = 0;
                loop {
                    match reader.next_record() {
                        Ok(Some(_)) => kept += 1,
                        Ok(None) => panic!("Truncated input was accepted"),
                        Err(_) => {
                            assert!(reader.next_record().is_err());
                            break;
                        }
                    }
                }
                assert!(kept < 3);
            }
        }
    }

    #[test]
    fn hostile_lengths_and_counts_are_rejected_before_allocation() {
        for offset in [8, 28] {
            for value in [u32::MAX, 0x7fff_ffff] {
                let mut bytes = old_page(4, 1);
                bytes[offset..offset + 4].copy_from_slice(&value.to_be_bytes());
                match Reader::open(Cursor::new(bytes)) {
                    Err(_) => (),
                    Ok(mut reader) => assert!(reader.next_record().is_err()),
                }
            }
        }
    }

    #[test]
    fn trailing_uncommitted_append_bytes_are_ignored() {
        let mut bytes = old_page(4, 1);
        let committed = bytes.len() as u64;
        bytes.extend([0xff; 13]);
        let mut reader = Reader::open(Cursor::new(bytes)).unwrap();
        assert!(reader.next_record().unwrap().is_some());
        assert_eq!(reader.next_record().unwrap(), None);
        assert_eq!(reader.committed_bytes(), committed);
    }
}
