#pragma once
#include "erase_geometry.h"
namespace notesis {
struct Sweep { float ax, ay, bx, by, radius; };
struct Span { double from, to; };
static_assert(sizeof(Sweep) == 20 && sizeof(Span) == 16);
// -1 unchanged, -2 invalid, -3 insufficient output (caller uses its reference implementation).
int clipPolyline(const Point* points, int count, const Sweep* sweeps, int sweepCount,
                 float brushRadius, Span* output, int capacity);
}
