#include "continuous_erase.h"
#include "erase_geometry.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <random>
#include <vector>
static void check(bool ok) { if(!ok) std::abort(); }
int main(int argc,char**) {
    using namespace notesis;
    Point line[]={{0,0},{1000,0}};Sweep disk{510,0,510,0,1};Span out[10];
    int n=clipPolyline(line,2,&disk,1,0,out,10);
    check(n==2 && std::abs(out[0].to-.509)<1e-9 && std::abs(out[1].from-.511)<1e-9);
    Sweep start{0,0,0,0,5};check(clipPolyline(line,2,&start,1,0,out,10)==1);
    check(std::abs(out[0].from-.005)<1e-9);
    Sweep tangent{510,1,510,1,1};check(clipPolyline(line,2,&tangent,1,0,out,10)==-1);
    check(clipPolyline(line,2,&disk,1,0,out,0)==-3);
    check(clipPolyline(line,2,&disk,1,-1,out,10)==-2);
    std::mt19937 rng(1180);std::uniform_real_distribution<float> pos(-50,50);
    for(int q=0;q<1000;++q) {
        std::vector<Point> points(200);for(auto& p:points)p={pos(rng),pos(rng)};
        Sweep sweeps[4];for(auto& s:sweeps)s={pos(rng),pos(rng),pos(rng),pos(rng),2.431f};
        std::vector<Span> result(1000);int found=clipPolyline(points.data(),points.size(),sweeps,4,0,result.data(),result.size());
        check(found>=-1);
        for(int i=0;i<found;++i) {
            check(result[i].from>=0 && result[i].to<=199 && result[i].to>result[i].from);
            if(i)check(result[i].from>result[i-1].to);
        }
    }
    std::cout<<"Continuous erase: sparse segments, contact, tangency, capacities and 1000 random polylines passed.\n";
    if(argc<=1)return 0;
    std::cout<<"HOST KERNEL ONLY: old 213 classifier called for 4 samples vs new continuous clipper called once for their union.\n"
             <<"Same original coordinates per batch; excludes JNI, stroke mesh construction, caching, rendering and device latency.\n";
    volatile long long checksum=0;
    for(int count:{64,512,4096}) {
        std::vector<Point> points(count);for(int i=0;i<count;++i)points[i]={float(i),0};
        std::vector<int> oldOutput(count*2);std::vector<Span> result(count);
        Sweep sweeps[4];for(int s=0;s<4;++s) {
            float a=count*.5f+s*.7f+.139f,b=a+.7f;sweeps[s]={a,0,b,0,4.417f};
        }
        constexpr int batches=2000;
        auto begin=std::chrono::steady_clock::now();
        for(int q=0;q<batches;++q)for(auto& s:sweeps)
            checksum+=cutRuns(points.data(),count,s.ax,s.ay,s.bx,s.by,s.radius,oldOutput.data(),oldOutput.size());
        auto middle=std::chrono::steady_clock::now();
        for(int q=0;q<batches;++q)checksum+=clipPolyline(points.data(),count,sweeps,4,0,result.data(),result.size());
        auto end=std::chrono::steady_clock::now();
        double oldMs=std::chrono::duration<double,std::milli>(middle-begin).count();
        double newMs=std::chrono::duration<double,std::milli>(end-middle).count();
        std::cout<<count<<" points, "<<batches<<" batches: old_ms="<<oldMs<<" new_ms="<<newMs
                 <<" kernel_time_reduction="<<(1-newMs/oldMs)*100<<"%\n";
    }
    check(checksum!=0);
}
