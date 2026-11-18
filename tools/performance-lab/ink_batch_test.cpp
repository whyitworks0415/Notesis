// Host checks for ink_batch.cpp: the mesh cache reader and the tile batching,
// run under the address and undefined-behaviour sanitizers by check-native.sh.
#include "ink_batch.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <vector>

using namespace notesis;

namespace {

int failures = 0;
#define CHECK(c) do { if (!(c)) { std::printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #c); ++failures; } } while (0)

struct Writer {
    std::vector<std::uint8_t> bytes;
    template <typename T> void put(T v) {
        const auto* p = reinterpret_cast<const std::uint8_t*>(&v);
        bytes.insert(bytes.end(), p, p + sizeof(T));
    }
};

// A cache as the app writes it (NativeInk.kt): header, then each stroke.
std::vector<std::uint8_t> cache(std::int64_t stamp, const std::vector<std::vector<float>>& strokes,
                                const std::vector<std::vector<std::uint16_t>>& indices) {
    Writer w;
    w.put<std::uint32_t>(kMeshMagic);
    w.put<std::uint32_t>(kMeshVersion);
    w.put<std::int64_t>(stamp);
    w.put<std::uint32_t>(std::uint32_t(strokes.size()));
    w.put<std::uint32_t>(0);
    for (std::size_t s = 0; s < strokes.size(); ++s) {
        const auto& p = strokes[s];
        float l = 1e9f, t = 1e9f, r = -1e9f, b = -1e9f;
        for (std::size_t i = 0; i < p.size(); i += 2) {
            l = std::min(l, p[i]); r = std::max(r, p[i]);
            t = std::min(t, p[i + 1]); b = std::max(b, p[i + 1]);
        }
        w.put<std::uint32_t>(0xff000000u | std::uint32_t(s * 40));
        w.put(l); w.put(t); w.put(r); w.put(b);
        w.put<std::uint32_t>(std::uint32_t(p.size() / 2));
        w.put<std::uint32_t>(std::uint32_t(indices[s].size()));
        for (float v : p) w.put(v);
        for (auto i : indices[s]) w.put(i);
        if (indices[s].size() % 2) w.put<std::uint16_t>(0);
    }
    return w.bytes;
}

void testRoundTrip() {
    auto bytes = cache(42, {{0, 0, 10, 0, 0, 10}, {100, 100, 110, 100, 110, 110, 100, 110}},
                       {{0, 1, 2}, {0, 1, 2, 0, 2, 3}});
    PageMeshes page;
    CHECK(parseMeshCache(bytes.data(), bytes.size(), 42, page));
    CHECK(page.strokes.size() == 2);
    CHECK(page.strokes[1].vertexCount == 4 && page.strokes[1].indexCount == 6);
    CHECK(page.positions.size() == 14);
    CHECK(page.strokes[0].right == 10 && page.strokes[1].left == 100);
}

void testRefusals() {
    auto bytes = cache(7, {{0, 0, 1, 0, 0, 1}}, {{0, 1, 2}});
    PageMeshes page;
    CHECK(!parseMeshCache(bytes.data(), bytes.size(), 8, page));          // the page file changed
    CHECK(page.strokes.empty());
    for (std::size_t cut = 0; cut < bytes.size(); ++cut) {               // cut short anywhere
        CHECK(!parseMeshCache(bytes.data(), cut, 7, page));
    }
    auto extra = bytes; extra.push_back(0);                              // trailing bytes
    CHECK(!parseMeshCache(extra.data(), extra.size(), 7, page));
    auto badIndex = cache(7, {{0, 0, 1, 0, 0, 1}}, {{0, 1, 3}});         // an index past the vertices
    CHECK(!parseMeshCache(badIndex.data(), badIndex.size(), 7, page));
    auto huge = bytes;                                                    // a count far past the data
    std::uint32_t lots = 0xffffffffu;
    std::memcpy(huge.data() + 16, &lots, 4);
    CHECK(!parseMeshCache(huge.data(), huge.size(), 7, page));
    CHECK(!parseMeshCache(nullptr, 0, 7, page));
}

void testFuzz() {
    // Random damage to a valid cache never reads out of bounds (the sanitizers would say).
    auto bytes = cache(3, {{0, 0, 5, 0, 0, 5, 5, 5}, {1, 1, 2, 2, 3, 1}}, {{0, 1, 2, 1, 3, 2}, {0, 1, 2}});
    std::mt19937 random(1234);
    PageMeshes page;
    for (int round = 0; round < 20000; ++round) {
        auto damaged = bytes;
        int flips = 1 + int(random() % 4);
        for (int f = 0; f < flips; ++f) damaged[random() % damaged.size()] ^= std::uint8_t(1u << (random() % 8));
        parseMeshCache(damaged.data(), damaged.size(), 3, page);
    }
}

void testBatch() {
    auto bytes = cache(1, {{0, 0, 10, 0, 0, 10}, {600, 600, 610, 600, 600, 610}, {5, 5, 6, 5, 5, 6}},
                       {{0, 1, 2}, {0, 1, 2}, {0, 1, 2}});
    PageMeshes page;
    CHECK(parseMeshCache(bytes.data(), bytes.size(), 1, page));
    TileBatch batch;
    batchTile(page, 0, 0, 2.0f, 512, 512, batch);   // covers page units 0..256
    CHECK(batch.strokeCount == 2);                  // the far stroke is left out
    CHECK(batch.vertexCount == 6);
    CHECK(batch.indices.size() == 6);
    CHECK(batch.indices[3] == 3 && batch.indices[5] == 5);  // the second stroke's indices follow on
    float x = 0, y = 0;
    std::memcpy(&x, batch.vertices.data() + kBatchStride * 1, 4);
    std::memcpy(&y, batch.vertices.data() + kBatchStride * 2 + 4, 4);
    CHECK(x == 20.0f && y == 20.0f);                // scaled into tile pixels
    CHECK(batch.vertices[11] == 0xff);              // opaque
    batchTile(page, 590, 590, 1.0f, 64, 64, batch); // only the far stroke
    CHECK(batch.strokeCount == 1);
    batchTile(page, 0, 0, 0.0f, 64, 64, batch);     // nonsense scale draws nothing
    CHECK(batch.strokeCount == 0 && batch.vertices.empty());
}

void benchmark() {
    // A dense page: 4000 strokes of 200 vertices each, batched for one tile.
    std::vector<std::vector<float>> strokes;
    std::vector<std::vector<std::uint16_t>> indices;
    std::mt19937 random(7);
    for (int s = 0; s < 4000; ++s) {
        std::vector<float> p;
        float x = float(random() % 1000), y = float(random() % 1400);
        for (int v = 0; v < 200; ++v) { p.push_back(x + v * 0.2f); p.push_back(y + float(v % 2)); }
        std::vector<std::uint16_t> idx;
        for (int v = 0; v + 2 < 200; ++v) { idx.push_back(std::uint16_t(v)); idx.push_back(std::uint16_t(v + 1)); idx.push_back(std::uint16_t(v + 2)); }
        strokes.push_back(p);
        indices.push_back(idx);
    }
    auto bytes = cache(9, strokes, indices);
    auto start = std::chrono::steady_clock::now();
    PageMeshes page;
    bool ok = parseMeshCache(bytes.data(), bytes.size(), 9, page);
    auto parsed = std::chrono::steady_clock::now();
    TileBatch batch;
    batchTile(page, 0, 0, 1.0f, 516, 516, batch);
    auto batched = std::chrono::steady_clock::now();
    std::printf("Mesh cache (host): %zu KB, parse %.2f ms, batch one tile (%u strokes) %.2f ms, ok=%d\n",
                bytes.size() / 1024,
                std::chrono::duration<double, std::milli>(parsed - start).count(), batch.strokeCount,
                std::chrono::duration<double, std::milli>(batched - parsed).count(), ok ? 1 : 0);
}

}  // namespace

int main(int argc, char** argv) {
    testRoundTrip();
    testRefusals();
    testFuzz();
    testBatch();
    if (argc > 1 && std::strcmp(argv[1], "benchmark") == 0) benchmark();
    if (failures) return 1;
    std::printf("ink batch: all checks passed\n");
    return 0;
}
