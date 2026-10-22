#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

namespace {
struct Vec { float x, y, z; };
Vec sub(Vec a, Vec b) { return {a.x-b.x,a.y-b.y,a.z-b.z}; }
Vec add(Vec a, Vec b) { return {a.x+b.x,a.y+b.y,a.z+b.z}; }
Vec cross(Vec a, Vec b) { return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x}; }
float dot(Vec a, Vec b) { return a.x*b.x+a.y*b.y+a.z*b.z; }
float coord(Vec a, int axis) { return axis==0?a.x:axis==1?a.y:a.z; }
struct Bounds { Vec lo,hi; };
Bounds merge(Bounds a, Bounds b) {
    return {{std::min(a.lo.x,b.lo.x),std::min(a.lo.y,b.lo.y),std::min(a.lo.z,b.lo.z)},
            {std::max(a.hi.x,b.hi.x),std::max(a.hi.y,b.hi.y),std::max(a.hi.z,b.hi.z)}};
}
struct Triangle {
    Vec a,e,f,n; float normal; Bounds bounds;
    Triangle(Vec anchor, Vec edge, Vec other):a(anchor),e(edge),f(other),n(cross(e,f)),normal(std::sqrt(dot(n,n))) {
        bounds=merge(merge({a,a},{add(a,e),add(a,e)}),{add(a,f),add(a,f)});
    }
    bool hit(Vec origin, Vec target, Vec direction, float bias) const {
        if (std::abs(dot(n,sub(target,a)))<=bias*normal) return false;
        const Vec s=sub(origin,a);
        if (std::abs(dot(n,s))<=.01f*normal) return false;
        const Vec p=cross(direction,f); const float det=dot(e,p);
        if (std::abs(det)<1e-9f) return false;
        const float inv=1.f/det; const float u=dot(s,p)*inv;
        if(u<0.f || u>1.f) return false;
        const Vec q=cross(s,e); const float v=dot(direction,q)*inv;
        if(v<0.f || u+v>1.f) return false;
        const float t=dot(f,q)*inv;
        return t>0.f && t<1.f;
    }
};
struct Node { Bounds bounds; int begin,end,left=-1,right=-1; };
class Index {
    std::vector<Triangle> faces;
    std::vector<Node> nodes;
    int build(int begin,int end) {
        Bounds b=faces[begin].bounds;
        for(int i=begin+1;i<end;++i) b=merge(b,faces[i].bounds);
        const int index=static_cast<int>(nodes.size());
        Bounds padded=b; const Vec epsilon={1e-5f,1e-5f,1e-5f};
        padded.lo=sub(b.lo,epsilon);padded.hi=add(b.hi,epsilon);
        nodes.push_back({padded,begin,end});
        if(end-begin>8) {
            const Vec extent=sub(b.hi,b.lo);
            const int axis=extent.x>=extent.y && extent.x>=extent.z?0:extent.y>=extent.z?1:2;
            const int mid=begin+(end-begin)/2;
            std::nth_element(faces.begin()+begin,faces.begin()+mid,faces.begin()+end,[axis](const Triangle& a,const Triangle& b) {
                return coord(a.bounds.lo,axis)+coord(a.bounds.hi,axis)<coord(b.bounds.lo,axis)+coord(b.bounds.hi,axis);
            });
            const int left=build(begin,mid),right=build(mid,end);
            nodes[index].left=left;nodes[index].right=right;
        }
        return index;
    }
    static bool intersects(Bounds b,Vec origin,Vec direction) {
        float near=0.f,far=1.f;
        for(int axis=0;axis<3;++axis) {
            const float d=coord(direction,axis),o=coord(origin,axis),lo=coord(b.lo,axis),hi=coord(b.hi,axis);
            if(d==0.f) { if(o<lo || o>hi) return false; }
            else {
                const float inverse=1.f/d,a=(lo-o)*inverse,c=(hi-o)*inverse;
                near=std::max(near,std::min(a,c));far=std::min(far,std::max(a,c));
                if(near>far) return false;
            }
        }
        return true;
    }
    bool hit(int index,Vec origin,Vec target,Vec direction,float bias) const {
        const Node& node=nodes[index];
        if(!intersects(node.bounds,origin,direction)) return false;
        if(node.left>=0) return hit(node.left,origin,target,direction,bias) || hit(node.right,origin,target,direction,bias);
        for(int i=node.begin;i<node.end;++i) if(faces[i].hit(origin,target,direction,bias)) return true;
        return false;
    }
public:
    explicit Index(const std::vector<float>& data) {
        faces.reserve(data.size()/9);
        for(size_t i=0;i+8<data.size();i+=9) {
            Triangle t({data[i],data[i+1],data[i+2]},{data[i+3],data[i+4],data[i+5]},{data[i+6],data[i+7],data[i+8]});
            if(t.normal>1e-7f) faces.push_back(t);
        }
        nodes.reserve(faces.size()*2);
        if(!faces.empty()) build(0,static_cast<int>(faces.size()));
    }
    bool blocked(Vec origin,Vec target,float bias) const {
        return !nodes.empty() && hit(0,origin,target,sub(target,origin),bias);
    }
};
}

// Single synchronous pure-math call: no Java array pinned during work, SDK access,
// persistent native handle, or scan-file access. All temporary memory is RAII-owned.
extern "C" JNIEXPORT jbooleanArray JNICALL
Java_com_example_roomsplat_domain_usecase_NativeMeshRayQuery_queryNative(
    JNIEnv* env,jclass,jfloatArray triangles,jfloat ox,jfloat oy,jfloat oz,jfloatArray targets,jfloat bias) {
    const jsize faceLength=env->GetArrayLength(triangles),targetLength=env->GetArrayLength(targets);
    if(faceLength%9 || targetLength%3 || !std::isfinite(bias) || bias<0.f) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"Invalid packed ray geometry");
        return nullptr;
    }
    std::vector<float> data(faceLength),points(targetLength);
    env->GetFloatArrayRegion(triangles,0,faceLength,data.data());
    if(env->ExceptionCheck()) return nullptr;
    env->GetFloatArrayRegion(targets,0,targetLength,points.data());
    if(env->ExceptionCheck()) return nullptr;
    const Index index(data);std::vector<jboolean> result(targetLength/3);
    for(jsize i=0;i<targetLength;i+=3) result[i/3]=index.blocked({ox,oy,oz},{points[i],points[i+1],points[i+2]},bias)?JNI_TRUE:JNI_FALSE;
    jbooleanArray output=env->NewBooleanArray(targetLength/3);
    if(output) env->SetBooleanArrayRegion(output,0,targetLength/3,result.data());
    return output;
}
