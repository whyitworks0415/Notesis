#include "ink_batch.h"

#include <cmath>
#include <cstring>

namespace notesis {
namespace {

// Reads little-endian values from the cache, never past its end.
class Reader {
public:
    Reader(const std::uint8_t* data, std::size_t size) : data_(data), size_(size) {}
    bool u32(std::uint32_t& v) { return take(&v, 4); }
    bool i64(std::int64_t& v) { return take(&v, 8); }
    bool f32(float& v) { return take(&v, 4) && std::isfinite(v); }
    bool bytes(void* to, std::size_t n) { return take(to, n); }
    std::size_t left() const { return size_ - at_; }
    bool skip(std::size_t n) {
        if (n > left()) return false;
        at_ += n;
        return true;
    }

private:
    bool take(void* to, std::size_t n) {
        if (n > left()) return false;
        std::memcpy(to, data_ + at_, n);
        at_ += n;
        return true;
    }
    const std::uint8_t* data_;
    std::size_t size_;
    std::size_t at_ = 0;
};

// Limits far past any real page: a corrupt count must not ask for gigabytes.
constexpr std::uint32_t kMaxStrokes = 1u << 20;
constexpr std::uint32_t kMaxStrokeVertices = 1u << 16;  // indices are 16-bit
constexpr std::uint32_t kMaxStrokeIndices = 1u << 22;

}  // namespace

bool parseMeshCache(const std::uint8_t* data, std::size_t size, std::int64_t expectedStamp, PageMeshes& out) {
    out = PageMeshes{};
    if (data == nullptr) return false;
    Reader in(data, size);
    std::uint32_t magic = 0, version = 0, count = 0, reserved = 0;
    std::int64_t stamp = 0;
    if (!in.u32(magic) || !in.u32(version) || !in.i64(stamp) || !in.u32(count) || !in.u32(reserved)) return false;
    if (magic != kMeshMagic || version != kMeshVersion || stamp != expectedStamp || count > kMaxStrokes) return false;
    PageMeshes page;
    page.stamp = stamp;
    page.strokes.reserve(count);
    for (std::uint32_t s = 0; s < count; ++s) {
        StrokeMesh stroke;
        std::uint32_t vertices = 0, indices = 0;
        if (!in.u32(stroke.argb) || !in.f32(stroke.left) || !in.f32(stroke.top) || !in.f32(stroke.right) ||
            !in.f32(stroke.bottom) || !in.u32(vertices) || !in.u32(indices)) return false;
        if (vertices > kMaxStrokeVertices || indices > kMaxStrokeIndices || indices % 3 != 0) return false;
        if (stroke.right < stroke.left || stroke.bottom < stroke.top) return false;
        // Room for what the counts promise, checked before anything is grown for it.
        const std::size_t need = std::size_t(vertices) * 8 + std::size_t(indices) * 2 + (indices % 2 ? 2 : 0);
        if (need > in.left()) return false;
        stroke.firstVertex = std::uint32_t(page.positions.size() / 2);
        stroke.vertexCount = vertices;
        stroke.firstIndex = std::uint32_t(page.indices.size());
        stroke.indexCount = indices;
        const std::size_t base = page.positions.size();
        page.positions.resize(base + std::size_t(vertices) * 2);
        for (std::uint32_t v = 0; v < vertices * 2; ++v) {
            if (!in.f32(page.positions[base + v])) return false;
        }
        const std::size_t ibase = page.indices.size();
        page.indices.resize(ibase + indices);
        if (indices > 0 && !in.bytes(page.indices.data() + ibase, std::size_t(indices) * 2)) return false;
        for (std::uint32_t i = 0; i < indices; ++i) {
            if (page.indices[ibase + i] >= vertices) return false;
        }
        if (indices % 2 && !in.skip(2)) return false;  // each stroke ends on a four-byte boundary
        page.strokes.push_back(stroke);
    }
    if (in.left() != 0) return false;
    out = std::move(page);
    return true;
}

void batchTile(const PageMeshes& page, float left, float top, float scale, int width, int height, TileBatch& out) {
    out.vertices.clear();
    out.indices.clear();
    out.vertexCount = 0;
    out.strokeCount = 0;
    if (!(scale > 0) || width <= 0 || height <= 0) return;
    const float right = left + width / scale;
    const float bottom = top + height / scale;
    auto reaches = [&](const StrokeMesh& s) {
        return s.vertexCount > 0 && s.indexCount > 0 &&
            !(s.right < left || s.left > right || s.bottom < top || s.top > bottom);
    };
    // Counted first and sized once: grown stroke by stroke, the buffers were copied again for each.
    std::size_t vertices = 0, indices = 0;
    for (const StrokeMesh& stroke : page.strokes) {
        if (!reaches(stroke)) continue;
        vertices += stroke.vertexCount;
        indices += stroke.indexCount;
    }
    if (vertices == 0 || vertices > 0xffffffffu) return;
    out.vertices.resize(vertices * kBatchStride);
    out.indices.resize(indices);
    std::uint8_t* to = out.vertices.data();
    std::uint32_t* index = out.indices.data();
    for (const StrokeMesh& stroke : page.strokes) {
        if (!reaches(stroke)) continue;
        const std::uint32_t base = out.vertexCount;
        const std::uint8_t a = std::uint8_t(stroke.argb >> 24), r = std::uint8_t(stroke.argb >> 16),
                           g = std::uint8_t(stroke.argb >> 8), b = std::uint8_t(stroke.argb);
        const float* from = page.positions.data() + std::size_t(stroke.firstVertex) * 2;
        for (std::uint32_t v = 0; v < stroke.vertexCount; ++v, to += kBatchStride) {
            const float x = (from[v * 2] - left) * scale;
            const float y = (from[v * 2 + 1] - top) * scale;
            std::memcpy(to, &x, 4);
            std::memcpy(to + 4, &y, 4);
            to[8] = r;
            to[9] = g;
            to[10] = b;
            to[11] = a;
        }
        const std::uint16_t* local = page.indices.data() + stroke.firstIndex;
        for (std::uint32_t i = 0; i < stroke.indexCount; ++i) *index++ = base + local[i];
        out.vertexCount += stroke.vertexCount;
        out.strokeCount++;
    }
}

}  // namespace notesis
