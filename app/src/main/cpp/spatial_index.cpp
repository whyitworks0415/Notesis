#include "spatial_index.h"
#include <algorithm>
#include <cmath>
#include <limits>

namespace notesis {
namespace {
bool finiteBox(float l, float t, float r, float b) {
    return std::isfinite(l) && std::isfinite(t) && std::isfinite(r) && std::isfinite(b) && l <= r && t <= b;
}
int build(Item* items, int first, int count, Node* nodes, int& used) {
    const int at = used++;
    Node& node = nodes[at];
    node = {items[first].left, items[first].top, items[first].right, items[first].bottom, -1, -1, first, count};
    for (int i = first + 1; i < first + count; ++i) {
        node.left = std::min(node.left, items[i].left);
        node.top = std::min(node.top, items[i].top);
        node.right = std::max(node.right, items[i].right);
        node.bottom = std::max(node.bottom, items[i].bottom);
    }
    if (count <= 8) return at;
    const bool x = double(node.right) - node.left >= double(node.bottom) - node.top;
    const int middle = first + count / 2;
    std::nth_element(items + first, items + middle, items + first + count, [x](const Item& a, const Item& b) {
        const double ca = x ? double(a.left) + a.right : double(a.top) + a.bottom;
        const double cb = x ? double(b.left) + b.right : double(b.top) + b.bottom;
        return ca < cb || (ca == cb && a.id < b.id);
    });
    node.count = 0;
    node.leftChild = build(items, first, middle - first, nodes, used);
    node.rightChild = build(items, middle, first + count - middle, nodes, used);
    return at;
}
}

int indexCapacity(int count) {
    if (count <= 0) return 0;
    int leaves = 1;
    while (leaves < (count + 7) / 8) leaves *= 2;
    return leaves * 2 - 1;
}

int buildIndex(Item* items, int count, Node* nodes, int capacity) {
    if (count < 0 || count > 200000 || capacity < indexCapacity(count)) return -1;
    if (count == 0) return 0;
    if (!items || !nodes) return -1;
    for (int i = 0; i < count; ++i)
        if (!finiteBox(items[i].left, items[i].top, items[i].right, items[i].bottom) || items[i].id < 0) return -1;
    int used = 0;
    build(items, 0, count, nodes, used);
    return used;
}

int queryIndex(const Item* items, int count, const Node* nodes, int nodeCount,
               float left, float top, float right, float bottom,
               std::int32_t* out, int capacity, int* examined) {
    if (examined) *examined = 0;
    if (!finiteBox(left, top, right, bottom)) return 0;
    if (count == 0 && nodeCount == 0) return 0;
    if (!items || !nodes || !out || count < 0 || nodeCount <= 0 || capacity < count) return -1;
    int stack[64];
    int pending = 1, found = 0;
    stack[0] = 0;
    while (pending) {
        const int index = stack[--pending];
        if (index < 0 || index >= nodeCount) return -1;
        const Node& node = nodes[index];
        if (node.right < left || node.left > right || node.bottom < top || node.top > bottom) continue;
        if (node.count == 0) {
            if (pending + 2 > 64) return -1;
            stack[pending++] = node.rightChild;
            stack[pending++] = node.leftChild;
        } else {
            if (node.first < 0 || node.count < 0 || node.first > count - node.count) return -1;
            for (int i = node.first; i < node.first + node.count; ++i) {
                if (examined) ++*examined;
                const Item& item = items[i];
                if (item.right >= left && item.left <= right && item.bottom >= top && item.top <= bottom)
                    out[found++] = item.id;
            }
        }
    }
    // The caller restores document order with its reusable bit set.
    return found;
}
}
