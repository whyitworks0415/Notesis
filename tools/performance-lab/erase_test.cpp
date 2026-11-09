#include "erase_geometry.h"
#include <cstdlib>
#include <iostream>
#include <limits>
#include <random>
#include <vector>
static void check(bool value) { if (!value) std::abort(); }
int main() {
    std::mt19937 random(273);
    std::uniform_real_distribution<float> coordinate(-1000.f, 1000.f);
    int native = 0;
    for (int count : {1, 2, 127, 128, 512, 4096, 65536}) {
        std::vector<notesis::Point> points(count);
        std::vector<std::int32_t> result(count * 2);
        for (auto& p : points) p = {coordinate(random), coordinate(random)};
        for (int query = 0; query < 100; ++query) {
            int runs = notesis::cutRuns(points.data(), count, coordinate(random), coordinate(random),
                coordinate(random), coordinate(random), 5.123f, result.data(), result.size());
            check(runs >= -2 && runs <= count / 2);
            if (runs >= -1) ++native;
            for (int i = 0; i < runs; ++i) {
                check(result[i * 2] >= 0 && result[i * 2 + 1] <= count && result[i * 2 + 1] - result[i * 2] >= 2);
                if (i) check(result[i * 2] >= result[(i - 1) * 2 + 1]);
            }
        }
        check(notesis::cutRuns(points.data(), count, 0, 0, 0, 0, -1, result.data(), result.size()) == -2);
        check(notesis::cutRuns(points.data(), count, 0, 0, 0, 0, 1, result.data(), 0) == -2);
        points[0].x = std::numeric_limits<float>::quiet_NaN();
        check(notesis::cutRuns(points.data(), count, 0, 0, 0, 0, 1, result.data(), result.size()) == -2);
    }
    check(native > 400);
    std::cout << "Erase geometry: 700 random traces, valid output ranges and invalid inputs checked.\n";
}
