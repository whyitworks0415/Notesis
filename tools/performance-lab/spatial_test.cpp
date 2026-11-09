#include "spatial_index.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <random>
#include <vector>

using notesis::Item;
using notesis::Node;
static void check(bool ok) { if (!ok) { std::cerr << "spatial check failed\n"; std::abort(); } }
static std::vector<int> linear(const std::vector<Item>& items, float l, float t, float r, float b) {
    std::vector<int> result;
    for (auto& i : items) if (i.right >= l && i.left <= r && i.bottom >= t && i.top <= b) result.push_back(i.id);
    std::sort(result.begin(), result.end());
    return result;
}
int main() {
    std::mt19937 random(0x51A71A1);
    std::uniform_real_distribution<float> position(-500000.f, 500000.f), extent(0.f, 1500.f);
    long long queries = 0;
    for (int count : {0, 1, 8, 9, 64, 511, 512, 8192, 32768, 200000}) {
        std::vector<Item> original;
        for (int i = 0; i < count; ++i) {
            const float x = position(random), y = position(random);
            original.push_back({x, y, x + extent(random), y + extent(random), i * 2});
        }
        auto packed = original;
        std::vector<Node> tree(count * 2);
        const int nodes = notesis::buildIndex(packed.data(), count, tree.data(), tree.size());
        check(nodes >= 0);
        std::vector<int> actual(count);
        for (int q = 0; q < (count > 32768 ? 40 : 500); ++q) {
            const float x = position(random), y = position(random);
            const float size = q % 17 == 0 ? 2000000.f : extent(random) * 20;
            const auto expected = linear(original, x, y, x + size, y + size);
            const int found = notesis::queryIndex(packed.data(), count, tree.data(), nodes, x, y, x + size, y + size,
                                                 actual.data(), actual.size());
            check(found >= 0);
            std::sort(actual.begin(), actual.begin() + found);
            check(expected == std::vector<int>(actual.begin(), actual.begin() + found));
            ++queries;
        }
    }
    // Inclusive edges, zero-area geometry, coincident centroids, and capacity checks.
    std::vector<Item> packed(1024, {0, 0, 0, 0, 0});
    for (int i = 0; i < 1024; ++i) packed[i].id = i;
    std::vector<Node> tree(2048);
    std::vector<int> out(1024);
    int nodes = notesis::buildIndex(packed.data(), 1024, tree.data(), 2048);
    check(notesis::queryIndex(packed.data(), 1024, tree.data(), nodes, 0, 0, 0, 0, out.data(), 1024) == 1024);
    check(notesis::queryIndex(packed.data(), 1024, tree.data(), nodes, 1, 1, 0, 0, out.data(), 1024) == 0);
    check(notesis::queryIndex(packed.data(), 1024, tree.data(), nodes, 0, 0, 0, 0, out.data(), 1) == -1);
    check(notesis::buildIndex(packed.data(), 1024, tree.data(), 1) == -1);
    packed[0].left = NAN;
    check(notesis::buildIndex(packed.data(), 1024, tree.data(), 2048) == -1);
    std::cout << "Correctness: " << queries << " random queries plus edge/capacity cases passed.\n";

    // Algorithm comparison on this host, excluding JNI and Android rendering.
    constexpr int count = 100000, rounds = 2000;
    std::vector<Item> original;
    for (int i = 0; i < count; ++i) {
        float x = (i % 1000) * 32.f, y = (i / 1000) * 32.f;
        original.push_back({x, y, x + 8, y + 8, i});
    }
    packed = original; tree.resize(count * 2); out.resize(count);
    auto start = std::chrono::steady_clock::now();
    nodes = notesis::buildIndex(packed.data(), count, tree.data(), tree.size());
    auto built = std::chrono::steady_clock::now();
    long long candidates = 0, totalFound = 0;
    for (int q = 0; q < rounds; ++q) {
        float x = (q * 17 % 990) * 32.f, y = (q * 11 % 90) * 32.f;
        int examined = 0;
        totalFound += notesis::queryIndex(packed.data(), count, tree.data(), nodes, x, y, x + 100, y + 100,
                                          out.data(), out.size(), &examined);
        candidates += examined;
    }
    auto queried = std::chrono::steady_clock::now();
    volatile long long linearFound = 0;
    for (int q = 0; q < rounds; ++q) {
        float x = (q * 17 % 990) * 32.f, y = (q * 11 % 90) * 32.f;
        for (const auto& i : original)
            if (i.right >= x && i.left <= x + 100 && i.bottom >= y && i.top <= y + 100) ++linearFound;
    }
    auto ended = std::chrono::steady_clock::now();
    check(totalFound == linearFound);
    check(candidates < static_cast<long long>(count) * rounds / 10);
    auto ms = [](auto a, auto b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
    std::cout << "Host only, " << count << " cached rectangles, " << rounds << " changing viewports:\n"
              << "Build ms: " << ms(start, built) << "\nBVH query ms: " << ms(built, queried)
              << "\nLinear query ms: " << ms(queried, ended)
              << "\nAverage examined rectangles: " << double(candidates) / rounds << " / " << count << "\n";
}
