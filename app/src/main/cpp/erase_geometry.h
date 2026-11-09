#pragma once
#include <cstdint>
namespace notesis {
struct Point { float x, y; };
static_assert(sizeof(Point) == 8);
// -1: unchanged; -2: invalid or numerically ambiguous, use the reference path.
int cutRuns(const Point* points, int count, float ax, float ay, float bx, float by,
            float reach, std::int32_t* output, int capacity);
}
