#include "erase_geometry.h"
#include <algorithm>
#include <cmath>
#include <limits>
namespace notesis {
namespace {
float pointDistance(Point p, Point a, Point b) {
    float dx = b.x - a.x, dy = b.y - a.y;
    float length = dx * dx + dy * dy;
    if (!std::isfinite(length)) return std::numeric_limits<float>::quiet_NaN();
    if (length <= 1e-12f) return float(std::hypot(double(p.x - a.x), double(p.y - a.y)));
    float t = std::clamp(((p.x - a.x) * dx + (p.y - a.y) * dy) / length, 0.f, 1.f);
    return float(std::hypot(double(p.x - (a.x + t * dx)), double(p.y - (a.y + t * dy))));
}
float cross(Point a, Point b, Point c) { return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x); }
bool opposite(float a, float b) { return (a > 0 && b < 0) || (a < 0 && b > 0); }
float segmentDistance(Point p, Point q, Point a, Point b) {
    if (opposite(cross(a, b, p), cross(a, b, q)) && opposite(cross(p, q, a), cross(p, q, b))) return 0.f;
    return std::min(std::min(pointDistance(p, a, b), pointDistance(q, a, b)),
                    std::min(pointDistance(a, p, q), pointDistance(b, p, q)));
}
bool ambiguous(float distance, float reach) {
    return !std::isfinite(distance) || std::abs(distance - reach) <= std::max(1e-5f, reach * 1e-5f);
}
}
int cutRuns(const Point* points, int count, float ax, float ay, float bx, float by,
            float reach, std::int32_t* output, int capacity) {
    if (count < 0 || count > 65536 || capacity < count * 2 || !points || !output ||
        !std::isfinite(ax) || !std::isfinite(ay) || !std::isfinite(bx) || !std::isfinite(by) ||
        !std::isfinite(reach) || reach < 0) return -2;
    for (int i = 0; i < count; ++i) if (!std::isfinite(points[i].x) || !std::isfinite(points[i].y)) return -2;
    Point a{ax, ay}, b{bx, by};
    int start = -1, runs = 0;
    bool changed = false;
    for (int i = 0; i < count; ++i) {
        float distance = pointDistance(points[i], a, b);
        if (ambiguous(distance, reach)) return -2;
        if (distance <= reach) {
            changed = true;
            if (start >= 0 && i - start >= 2) { output[runs * 2] = start; output[runs++ * 2 + 1] = i; }
            start = -1;
            continue;
        }
        if (start >= 0 && i > 0) {
            float between = segmentDistance(points[i - 1], points[i], a, b);
            if (ambiguous(between, reach)) return -2;
            if (between <= reach) {
                changed = true;
                if (i - start >= 2) { output[runs * 2] = start; output[runs++ * 2 + 1] = i; }
                start = i;
                continue;
            }
        }
        if (start < 0) start = i;
    }
    if (start >= 0 && count - start >= 2) { output[runs * 2] = start; output[runs++ * 2 + 1] = count; }
    return changed ? runs : -1;
}
}
