#pragma once
#include <cstdint>

namespace notesis {
struct Item { float left, top, right, bottom; std::int32_t id; };
struct Node {
    float left, top, right, bottom;
    std::int32_t leftChild, rightChild, first, count;
};
static_assert(sizeof(Item) == 20);
static_assert(sizeof(Node) == 32);
// Buffers belong to the caller. No pointer survives a call, including through JNI.
int indexCapacity(int count);
int buildIndex(Item* items, int count, Node* nodes, int capacity);
int queryIndex(const Item* items, int count, const Node* nodes, int nodeCount,
               float left, float top, float right, float bottom,
               std::int32_t* out, int capacity, int* examined = nullptr);
}
