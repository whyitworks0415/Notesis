#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace notesis {

// Empty means unchanged. Modes: loaded pen inputs, finished pen, other freehand.
std::vector<std::uint8_t> tidyInputs(const float* xs, const float* ys,
    const std::int64_t* times, int count, float width, int mode);

// NTSS v1-v4, in file order. Each row is tool, colour, size bits, epsilon bits,
// input offset, input length, layer and nib. Input payloads remain in the source.
// An empty result means invalid or incomplete, never a partially accepted page.
std::vector<std::int32_t> scanPage(const std::uint8_t* bytes, std::size_t size);

} // namespace notesis
