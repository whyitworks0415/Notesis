#include "note_core.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <stdexcept>

namespace notesis {
namespace {
float distance(float x, float y) {
    // Kotlin's Float hypot evaluates in double before rounding to float.
    return static_cast<float>(std::hypot(double(x), double(y)));
}

std::int64_t elapsed(std::int64_t later, std::int64_t earlier) {
    // Driver timestamps normally increase. Keep JVM subtraction semantics even
    // for corrupt input without introducing signed-overflow undefined behaviour.
    const auto bits = std::uint64_t(later) - std::uint64_t(earlier);
    std::int64_t value;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

bool spur(const float* x, const float* y, const std::int64_t* t, int i, float width) {
    const auto dt = elapsed(t[i + 1], t[i - 1]);
    if (dt < 0 || dt > 40 || width <= 0) return false;
    const float ax = x[i] - x[i - 1], ay = y[i] - y[i - 1];
    const float bx = x[i + 1] - x[i], by = y[i + 1] - y[i];
    const float a = distance(ax, ay), b = distance(bx, by);
    if (a < .5f || b < .5f || a > std::max(2.f, width * 1.5f)) return false;
    const float gap = distance(x[i + 1] - x[i - 1], y[i + 1] - y[i - 1]);
    return gap <= std::min(2.f, width * .4f) && ax * bx + ay * by < -.65f * a * b;
}

bool turnsBack(const float* x, const float* y, int k) {
    const float ax = x[k] - x[k - 1], ay = y[k] - y[k - 1];
    const float bx = x[k + 1] - x[k], by = y[k + 1] - y[k];
    const float a = distance(ax, ay), b = distance(bx, by);
    if (a < 1e-4f || b < 1e-4f) return false;
    return (ax * bx + ay * by) / (a * b) < -.2f;
}

class Extreme {
    const float* values;
    bool highest;
    std::vector<int> queue;
    int head = 0, tail = 0;
public:
    Extreme(const float* v, int count, bool high) : values(v), highest(high), queue(count) {}
    void push(int i) {
        while (tail > head && (highest ? values[queue[tail - 1]] <= values[i]
                                      : values[queue[tail - 1]] >= values[i])) --tail;
        queue[tail++] = i;
    }
    void dropBefore(int i) { while (head < tail && queue[head] < i) ++head; }
    int front() const { return queue[head]; }
};

std::vector<std::uint8_t> dwell(const float* x, const float* y, int count, float radius) {
    if (count < 5) return {};
    const int none = count - 1;
    std::vector<int> nextTurn(count);
    nextTurn[none] = none;
    int upcoming = none;
    for (int k = count - 2; k >= 1; --k) {
        if (turnsBack(x, y, k)) upcoming = k;
        nextTurn[k] = upcoming;
    }
    if (nextTurn[1] == none) return {};
    Extreme highX(x, count, true), lowX(x, count, false);
    Extreme highY(y, count, true), lowY(y, count, false);
    int pushed = 0, i = 1;
    std::vector<std::uint8_t> keep;
    while (i < count - 1) {
        const int turn = nextTurn[i];
        if (turn == none) break;
        const int reach = std::max(turn, i + 2);
        if (reach > count - 2) break;
        pushed = std::max(pushed, i + 1);
        while (pushed <= reach) {
            highX.push(pushed); lowX.push(pushed); highY.push(pushed); lowY.push(pushed);
            ++pushed;
        }
        highX.dropBefore(i + 1); lowX.dropBefore(i + 1);
        highY.dropBefore(i + 1); lowY.dropBefore(i + 1);
        const float cx = x[i], cy = y[i];
        bool folds = !(x[highX.front()] - cx > radius || cx - x[lowX.front()] > radius ||
                       y[highY.front()] - cy > radius || cy - y[lowY.front()] > radius);
        if (folds) for (int k = i + 1; k <= reach; ++k) {
            if (!(distance(x[k] - cx, y[k] - cy) <= radius)) { folds = false; break; }
        }
        if (!folds) { ++i; continue; }
        int j = reach;
        while (j + 1 < count - 1 && distance(x[j + 1] - cx, y[j + 1] - cy) <= radius) ++j;
        int back = i - 1;
        while (back > 0 && distance(cx - x[back], cy - y[back]) < radius) --back;
        const float inX = cx - x[back], inY = cy - y[back];
        int best = i;
        float bestReach = -std::numeric_limits<float>::infinity();
        for (int k = i; k <= j; ++k) {
            const float along = (x[k] - cx) * inX + (y[k] - cy) * inY;
            if (along > bestReach) { bestReach = along; best = k; }
        }
        if (keep.empty()) keep.assign(count, 1);
        for (int k = i; k <= j; ++k) if (k != best) keep[k] = 0;
        i = j + 1;
    }
    return keep;
}

std::vector<std::uint8_t> pen(const float* x, const float* y, const std::int64_t* t,
                            int count, float radius, bool start) {
    std::vector<std::uint8_t> keep;
    if (start && count >= 3) {
        int first = -1;
        for (int i = 1; i < count; ++i) {
            if (elapsed(t[i], t[0]) > 100) break;
            if (distance(x[i] - x[0], y[i] - y[0]) >= radius) { first = i; break; }
        }
        if (first >= 2) {
            keep.assign(count, 1);
            std::fill(keep.begin() + 1, keep.begin() + first, 0);
        }
    }
    auto folded = dwell(x, y, count, radius);
    if (keep.empty()) return folded;
    if (!folded.empty()) for (int i = 0; i < count; ++i) if (!folded[i]) keep[i] = 0;
    return keep;
}

std::int32_t bigInt(const std::uint8_t* p) {
    const std::uint32_t bits = std::uint32_t(p[0]) << 24 | std::uint32_t(p[1]) << 16 |
                               std::uint32_t(p[2]) << 8 | p[3];
    std::int32_t result;
    std::memcpy(&result, &bits, sizeof(result));
    return result;
}
} // namespace

std::vector<std::uint8_t> tidyInputs(const float* x, const float* y,
    const std::int64_t* t, int count, float width, int mode) {
    if (count < 0 || count > 1'048'576 || mode < 0 || mode > 2 ||
        !std::isfinite(width) || (count && (!x || !y || !t)))
        throw std::invalid_argument("Invalid tidy samples");
    if (count < 3) return {};
    const float radius = std::max(width * .25f, .3f);
    if (mode == 0) return pen(x, y, t, count, radius, true);
    int dropStart = -1, dropEnd = -1;
    if (count >= 4) {
        for (int i = 1; i <= std::min(5, count - 2); ++i) {
            if (spur(x, y, t, i, width) && elapsed(t[i], t[0]) <= 48) { dropStart = i; break; }
        }
        for (int i = count - 2; i >= std::max(1, count - 6); --i) {
            if ((dropStart < 0 || std::abs(i - dropStart) > 1) && spur(x, y, t, i, width) &&
                elapsed(t[count - 1], t[i]) <= 48) { dropEnd = i; break; }
        }
    }
    if (dropStart < 0 && dropEnd < 0) return pen(x, y, t, count, radius, mode == 1);
    const int left = count - (dropStart >= 0 ? 1 : 0) - (dropEnd >= 0 ? 1 : 0);
    std::vector<float> lx(left), ly(left);
    std::vector<std::int64_t> lt(left);
    int at = 0;
    for (int i = 0; i < count; ++i) {
        if (i == dropStart || i == dropEnd) continue;
        lx[at] = x[i]; ly[at] = y[i]; lt[at++] = t[i];
    }
    const auto rest = pen(lx.data(), ly.data(), lt.data(), left, radius, mode == 1);
    std::vector<std::uint8_t> keep(count, 0);
    at = 0;
    for (int i = 0; i < count; ++i) {
        if (i == dropStart || i == dropEnd) continue;
        keep[i] = rest.empty() ? 1 : rest[at];
        ++at;
    }
    return keep;
}

std::vector<std::int32_t> scanPage(const std::uint8_t* bytes, std::size_t size) {
    constexpr std::size_t maxBytes = 96 * 1024 * 1024;
    if (!bytes || size < 12 || size > maxBytes || bigInt(bytes) != 0x4e545353) return {};
    const auto version = bigInt(bytes + 4), count = bigInt(bytes + 8);
    if (version < 1 || version > 4 || count < 0 || count > 1'000'000) return {};
    const std::size_t trailer = (version >= 3 ? 1 : 0) + (version >= 4 ? 1 : 0);
    // Reject impossible counts before allocating an index from untrusted bytes.
    if (std::size_t(count) > (size - 12) / (20 + trailer)) return {};
    std::vector<std::int32_t> index;
    index.reserve(1 + std::size_t(count) * 8);
    index.push_back(version);
    std::size_t at = 12;
    for (int i = 0; i < count; ++i) {
        if (size - at < 20) return {};
        const auto length = bigInt(bytes + at + 16);
        if (length < 0 || std::size_t(length) > size - at - 20 ||
            trailer > size - at - 20 - std::size_t(length)) return {};
        for (int field = 0; field < 4; ++field) index.push_back(bigInt(bytes + at + field * 4));
        const auto payload = at + 20;
        index.push_back(static_cast<std::int32_t>(payload));
        index.push_back(length);
        at = payload + std::size_t(length);
        index.push_back(version >= 3 ? bytes[at++] : 0);
        // The angle uses a signed byte; 0x80 is the app's NO_NIB sentinel.
        const int nib = version >= 4 ? bytes[at++] : 128;
        index.push_back(nib >= 128 ? nib - 256 : nib);
    }
    // The stable reader ignores trailing bytes too. Append recovery relies on it.
    return index;
}
} // namespace notesis
