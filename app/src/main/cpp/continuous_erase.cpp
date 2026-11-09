#include "continuous_erase.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <vector>
namespace notesis {
namespace {
constexpr double eps = 1e-9;
bool capsule(Point p, Point q, const Sweep& s, float brushRadius, Span& result) {
    const double r = double(s.radius + brushRadius);
    const double px=p.x, py=p.y, qx=q.x, qy=q.y, ax=s.ax, ay=s.ay, bx=s.bx, by=s.by;
    if (std::max(px,qx)<std::min(ax,bx)-r || std::min(px,qx)>std::max(ax,bx)+r ||
        std::max(py,qy)<std::min(ay,by)-r || std::min(py,qy)>std::max(ay,by)+r) return false;
    const double dx=qx-px, dy=qy-py, length=dx*dx+dy*dy;
    double first=std::numeric_limits<double>::infinity(), last=-first;
    auto include=[&](double lo,double hi) {
        lo=std::max(0.0,lo); hi=std::min(1.0,hi);
        if (hi>lo+eps) {first=std::min(first,lo);last=std::max(last,hi);}
    };
    auto disk=[&](double x,double y) {
        if (length<=1e-24) { if ((px-x)*(px-x)+(py-y)*(py-y)<r*r) include(0,1); return; }
        double middle=((x-px)*dx+(y-py)*dy)/length;
        double ex=px+middle*dx-x, ey=py+middle*dy-y;
        double remaining=r*r-ex*ex-ey*ey;
        if (remaining<=0) return;
        double half=std::sqrt(remaining/length); include(middle-half,middle+half);
    };
    disk(ax,ay);
    const double sx=bx-ax, sy=by-ay, sweepLength=std::hypot(sx,sy);
    if (sweepLength>1e-12) {
        disk(bx,by);
        double lo=0, hi=1;
        auto slab=[&](double origin,double delta,double low,double high) {
            if (std::abs(delta)<=1e-12) return origin>=low && origin<=high;
            double a=(low-origin)/delta, b=(high-origin)/delta;
            lo=std::max(lo,std::min(a,b));hi=std::min(hi,std::max(a,b));return hi>=lo;
        };
        if (slab(((px-ax)*sx+(py-ay)*sy)/sweepLength,(dx*sx+dy*sy)/sweepLength,0,sweepLength) &&
            slab(((px-ax)*-sy+(py-ay)*sx)/sweepLength,(-dx*sy+dy*sx)/sweepLength,-r,r)) include(lo,hi);
    }
    result={first,last};return last>first+eps;
}
}
int clipPolyline(const Point* points,int count,const Sweep* sweeps,int sweepCount,
                 float brushRadius,Span* output,int capacity) {
    if (!points || !sweeps || !output || count<1 || count>65536 || sweepCount<1 || sweepCount>128 ||
        capacity<0 || !std::isfinite(brushRadius) || brushRadius<0) return -2;
    for (int i=0;i<count;++i) if (!std::isfinite(points[i].x)||!std::isfinite(points[i].y)) return -2;
    for (int i=0;i<sweepCount;++i) {
        const auto& s=sweeps[i];
        if (!std::isfinite(s.ax)||!std::isfinite(s.ay)||!std::isfinite(s.bx)||!std::isfinite(s.by)||
            !std::isfinite(s.radius)||s.radius<0) return -2;
    }
    if (count==1) {
        for (int i=0;i<sweepCount;++i) { Span cut; if(capsule(points[0],points[0],sweeps[i],brushRadius,cut)) return 0; }
        return -1;
    }
    int found=0;double cursor=0;bool changed=false;
    std::vector<Span> cuts;cuts.reserve(sweepCount);
    auto keep=[&](double a,double b) {if(found<capacity)output[found]={a,b};++found;};
    for (int edge=0;edge<count-1;++edge) {
        cuts.clear();
        for(int i=0;i<sweepCount;++i) {Span cut;if(capsule(points[edge],points[edge+1],sweeps[i],brushRadius,cut))cuts.push_back(cut);}
        std::sort(cuts.begin(),cuts.end(),[](const Span& a,const Span& b){return a.from<b.from;});
        for (const auto& cut:cuts) {
            double a=edge+cut.from,b=edge+cut.to;
            if(b<=cursor+eps)continue;
            if(a>cursor+eps)keep(cursor,a);
            cursor=std::max(cursor,b);changed=true;
        }
    }
    if(!changed)return -1;
    if(count-1.0>cursor+eps)keep(cursor,count-1.0);
    return found>capacity?-3:found;
}
}
