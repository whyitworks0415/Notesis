#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

// A page's ink as triangles, read from its mesh cache (<page>.mesh), and the
// batching that turns the strokes under one tile into a single vertex and
// index buffer - one draw call for the whole tile instead of one per stroke.
// Plain C++ so the host checks (tools/performance-lab) can run it under the
// address and undefined-behaviour sanitizers.
namespace notesis {

constexpr std::uint32_t kMeshMagic = 0x48534d4e;  // "NMSH", little-endian
constexpr std::uint32_t kMeshVersion = 1;

struct StrokeMesh {
    std::uint32_t argb = 0;
    float left = 0, top = 0, right = 0, bottom = 0;
    std::uint32_t firstVertex = 0, vertexCount = 0;  // into PageMeshes::positions (pairs)
    std::uint32_t firstIndex = 0, indexCount = 0;    // into PageMeshes::indices, local to the stroke
};

struct PageMeshes {
    std::int64_t stamp = 0;
    std::vector<StrokeMesh> strokes;
    std::vector<float> positions;          // x, y per vertex, page units
    std::vector<std::uint16_t> indices;    // per stroke, local to its vertices
};

// Reads a cache file's bytes. False - and [out] left empty - when it is not a
// cache, is of another version, was made for another [expectedStamp] (the page
// file changed since), or is cut short or inconsistent anywhere.
bool parseMeshCache(const std::uint8_t* data, std::size_t size, std::int64_t expectedStamp, PageMeshes& out);

// One tile's draw: interleaved vertices (x, y in tile pixels as floats, then
// the colour as four bytes r, g, b, a) and 32-bit indices into them.
struct TileBatch {
    std::vector<std::uint8_t> vertices;  // kBatchStride bytes each
    std::vector<std::uint32_t> indices;
    std::uint32_t vertexCount = 0;
    std::uint32_t strokeCount = 0;
};
constexpr std::size_t kBatchStride = 12;

// The strokes of [page] that reach the tile whose top-left corner is
// ([left], [top]) in page units, drawn at [scale] pixels per unit into a
// [width] by [height] tile, in their drawing order. A stroke is taken whole:
// the GPU clips what falls outside the tile.
void batchTile(const PageMeshes& page, float left, float top, float scale, int width, int height, TileBatch& out);

}  // namespace notesis
