#include "note_core.h"
#include <cassert>
#include <chrono>
#include <cmath>
#include <iostream>
#include <limits>
#include <random>
#include <stdexcept>
#include <vector>

namespace {
void word(std::vector<std::uint8_t>& b, std::uint32_t value) {
    for (int shift = 24; shift >= 0; shift -= 8) b.push_back((value >> shift) & 255);
}
std::vector<std::uint8_t> page(int version, int count) {
    std::vector<std::uint8_t> b;
    word(b, 0x4e545353); word(b, version); word(b, count);
    for (int i = 0; i < count; ++i) {
        word(b, 0x100 + i); word(b, 0xff010203); word(b, 0x3f800000); word(b, 0x3a83126f);
        word(b, 3); b.insert(b.end(), {3, 2, 1});
        if (version >= 3) b.push_back(7);
        if (version >= 4) b.push_back(128);
    }
    return b;
}
}

int main(int argc, char**) {
    for (int version = 1; version <= 4; ++version) {
        for (int count : {0, 1, 31, 1000}) {
            const auto b = page(version, count);
            const auto scan = notesis::scanPage(b.data(), b.size());
            assert(scan.size() == 1 + std::size_t(count) * 8 && scan[0] == version);
            for (int i = 0; i < count; ++i) {
                const int at = 1 + i * 8;
                assert(scan[at] == 0x100 + i && scan[at + 5] == 3);
                assert(b[scan[at + 4]] == 3 && scan[at + 6] == (version >= 3 ? 7 : 0));
                assert(scan[at + 7] == -128);
            }
            for (std::size_t cut = 0; cut < b.size(); ++cut)
                assert(notesis::scanPage(b.data(), cut).empty());
        }
    }
    auto bad = page(4, 1);
    bad[28] = 0x7f; // A payload length larger than the file.
    assert(notesis::scanPage(bad.data(), bad.size()).empty());
    assert(notesis::scanPage(nullptr, 12).empty());

    const float x[] = {0, 0, .1f, .2f, 3, 4};
    const float y[] = {0, 0, 0, 0, 0, 0};
    const std::int64_t t[] = {0, 4, 8, 12, 16, 20};
    const auto mask = notesis::tidyInputs(x, y, t, 6, 4, 0);
    assert(mask == std::vector<std::uint8_t>({1, 0, 0, 0, 1, 1}));
    bool rejected = false;
    try { notesis::tidyInputs(x, y, t, -1, 4, 0); }
    catch (const std::invalid_argument&) { rejected = true; }
    assert(rejected);

    // Exercise malformed lengths, coordinates and wrapped timestamps under ASan/UBSan.
    std::mt19937 random(424242);
    for (int run = 0; run < 3000; ++run) {
        std::vector<std::uint8_t> bytes(random() % 1024);
        for (auto& v : bytes) v = random();
        if (bytes.size() >= 12 && run % 2 == 0) {
            bytes[0] = 'N'; bytes[1] = 'T'; bytes[2] = 'S'; bytes[3] = 'S';
        }
        notesis::scanPage(bytes.data(), bytes.size());
        const int count = 3 + random() % 256;
        std::vector<float> xs(count), ys(count);
        std::vector<std::int64_t> ts(count);
        for (int i = 0; i < count; ++i) {
            xs[i] = float(int(random() % 101) - 50) * .01f;
            ys[i] = float(int(random() % 101) - 50) * .01f;
            ts[i] = (i & 1) ? std::numeric_limits<std::int64_t>::min() : std::numeric_limits<std::int64_t>::max();
        }
        if (run % 3 == 0) xs[random() % count] = std::numeric_limits<float>::quiet_NaN();
        for (int mode = 0; mode < 3; ++mode) {
            const auto result = notesis::tidyInputs(xs.data(), ys.data(), ts.data(), count, 4, mode);
            assert(result.empty() || (result.size() == std::size_t(count) && result.front() && result.back()));
        }
    }
    std::cout << "Native page framing and cleanup: correctness and malformed-input checks passed\n";
    if (argc > 1) {
        const auto bytes = page(4, 5000);
        const auto start = std::chrono::steady_clock::now();
        for (int i = 0; i < 1000; ++i) {
            const auto result = notesis::scanPage(bytes.data(), bytes.size());
            assert(result.size() == 40001);
        }
        const auto us = std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - start).count();
        std::cout << "Host only, C++ framing 5000 synthetic records: " << us / 1000 << " us/iteration\n";
        std::cout << "Excludes disk I/O, JNI, Ink decoding, mesh generation, UI and display. Not an app speedup.\n";
    }
}
