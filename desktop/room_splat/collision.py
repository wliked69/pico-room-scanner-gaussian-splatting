"""Bounded CPU reconstruction of an approximate static collision shell from splats."""
from __future__ import annotations
import json
import time
import uuid
from pathlib import Path
import numpy as np
from .capture import digest

QUALITY = {'快速': 80, '标准': 128, '精细': 192}
TYPES = {'float':'f4','float32':'f4','double':'f8','float64':'f8','uchar':'u1','uint8':'u1',
         'char':'i1','int8':'i1','short':'i2','int16':'i2','ushort':'u2','uint16':'u2',
         'int':'i4','int32':'i4','uint':'u4','uint32':'u4'}

def read_splats(path, check=lambda: None):
    with Path(path).open('rb') as source:
        if source.readline().strip() != b'ply': raise ValueError('不是 PLY 模型。')
        fields=[]; count=None; current=''; fmt=''; length=0
        while True:
            check(); line=source.readline(); length+=len(line)
            if not line or length>65536: raise ValueError('PLY 文件头损坏。')
            parts=line.decode('ascii').strip().split()
            if not parts: continue
            if parts[0]=='end_header': break
            if parts[0]=='format': fmt=parts[1]
            elif parts[0]=='element':
                current=parts[1]
                if current=='vertex': count=int(parts[2])
                elif count is None and int(parts[2]): raise ValueError('不支持顶点前包含其他元素的 PLY。')
            elif parts[0]=='property' and current=='vertex':
                if len(parts)!=3 or parts[1] not in TYPES: raise ValueError('不支持该 PLY 顶点类型。')
                fields.append((parts[2],TYPES[parts[1]]))
        if count is None or not 30<=count<=5_000_000 or not {'x','y','z'}.issubset(dict(fields)):
            raise ValueError('模型需要 30–5000000 个有效顶点及 x/y/z 坐标。')
        if len(set(name for name,_ in fields)) != len(fields): raise ValueError('PLY 属性重复。')
        if fmt in ('binary_little_endian','binary_big_endian'):
            endian='<' if fmt=='binary_little_endian' else '>'
            dtype=np.dtype([(name,endian+kind) for name,kind in fields])
            data=np.fromfile(source,dtype=dtype,count=count)
            if len(data)!=count: raise ValueError('PLY 顶点数据不完整。')
        elif fmt=='ascii':
            values=np.loadtxt(source,max_rows=count,ndmin=2)
            if values.shape!=(count,len(fields)): raise ValueError('PLY 顶点数据不完整。')
            data={name:values[:,i] for i,(name,_) in enumerate(fields)}
        else: raise ValueError('不支持该 PLY 编码。')
    points=np.column_stack([data[name] for name in ('x','y','z')]).astype(np.float32)
    opacity=np.ones(count,np.float32)
    if 'opacity' in dict(fields): opacity=1/(1+np.exp(-np.clip(data['opacity'].astype(np.float32),-30,30)))
    keep=np.isfinite(points).all(axis=1)&np.isfinite(opacity)&(opacity>=.05)
    check()
    return points[keep],opacity[keep],count

def reconstruct_shell(points, weights, resolution=128, check=lambda:None):
    from scipy.ndimage import gaussian_filter, binary_closing, label
    from skimage.measure import marching_cubes
    if len(points)<30: raise ValueError('过滤后有效模型点不足，无法生成碰撞网格。')
    if resolution not in QUALITY.values(): raise ValueError('无效网格精度。')
    low,high=np.quantile(points,[.001,.999],axis=0)
    extent=high-low; longest=float(extent.max())
    if not np.isfinite(longest) or longest<1e-6: raise ValueError('模型空间范围不足。')
    cell=longest/resolution; origin=low-cell*4
    shape=np.maximum(np.ceil(extent/cell).astype(int)+9,9)
    keep=((points>=low)&(points<=high)).all(axis=1)
    indexes=np.floor((points[keep]-origin)/cell).astype(int)
    volume=np.zeros(tuple(shape),np.float32)
    np.add.at(volume,tuple(indexes.T),weights[keep])
    np.minimum(volume,1.,out=volume)
    check()
    solid=gaussian_filter(volume,.9,mode='constant')>=.09
    solid=binary_closing(solid,iterations=1)
    labels,n=label(solid)
    counts=np.bincount(labels.ravel()); counts[0]=0
    solid=counts[labels]>=12
    if not solid.any(): raise ValueError('模型表面过于稀疏，请使用更低精度或补充扫描。')
    check()
    density=gaussian_filter(solid.astype(np.float32),.65,mode='constant')
    vertices,faces,_,_=marching_cubes(density,level=.35,spacing=(cell,cell,cell),allow_degenerate=False)
    vertices=(vertices+origin).astype(np.float32)
    # Ensure outward winding in the source right-handed coordinates.
    tri=vertices[faces]
    if np.einsum('ij,ij->i',tri[:,0],np.cross(tri[:,1],tri[:,2])).sum()<0: faces=faces[:,[0,2,1]]
    check()
    return vertices,faces,dict(voxel_size=float(cell),resolution=resolution,grid_shape=shape.tolist(),
                               points_used=int(keep.sum()),vertices=len(vertices),triangles=len(faces))

def export_collision(source, parent=None, quality='标准', log=print, check=lambda:None):
    source=Path(source).resolve()
    if quality not in QUALITY: raise ValueError('无效网格精度。')
    log('碰撞网格：读取高斯模型，过滤低透明度点…')
    points,weights,total=read_splats(source,check)
    log('碰撞网格：CPU 表面重建 · '+quality+'；保持原模型坐标。')
    vertices,faces,stats=reconstruct_shell(points,weights,QUALITY[quality],check)
    output=Path(parent or source.parent)/('collision-'+time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8])
    output.mkdir(parents=True,exist_ok=False)
    mesh=output/'collision.obj'; temporary=output/'collision.obj.partial'
    try:
        with temporary.open('w',encoding='ascii') as file:
            file.write('# RoomSplat approximate static collision mesh; original PLY coordinates\no RoomSplatCollision\n')
            for start in range(0,len(vertices),10000):
                check(); file.writelines('v %.9g %.9g %.9g\n'%tuple(v) for v in vertices[start:start+10000])
            for start in range(0,len(faces),10000):
                check(); file.writelines('f %d %d %d\n'%tuple(f+1) for f in faces[start:start+10000])
        check(); temporary.rename(mesh)
        report=dict(source=str(source),source_sha256=digest(source,check),source_vertices=total,
                    method='opacity-filtered voxel shell + Lewiner marching cubes',approximate=True,
                    units='source PLY units; not calibrated meters',quality=quality,**stats)
        (output/'collision-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        (output/'Unity使用说明.txt').write_text(
            '将 collision.obj 拖入 Unity 的 Assets。将模型放入场景，在同一物体添加 MeshCollider，Mesh 选择导入网格。\n'
            '静态房间保持 Convex 未勾选，不添加非运动学 Rigidbody。需要移动的物体应另外使用简化的凸碰撞体。\n'
            '这是从高斯模型生成的近似碰撞表面，可能遗漏细小物体或封闭小孔；请检查门口及通道。\n'
            '坐标及缩放沿用原始 PLY，并非已标定的米；高斯渲染器若翻转坐标轴，网格应施加相同变换。\n',encoding='utf-8')
        log(f'碰撞网格完成：{len(faces)} 个三角形 · {mesh}')
        return mesh
    finally:
        if temporary.exists(): temporary.unlink()

def read_obj(path):
    vertices=[]; faces=[]
    with Path(path).open(encoding='utf-8') as file:
        for line in file:
            parts=line.split()
            if not parts: continue
            if parts[0]=='v': vertices.append([float(x) for x in parts[1:4]])
            elif parts[0]=='f':
                ids=[int(x.split('/')[0])-1 for x in parts[1:]]
                for i in range(1,len(ids)-1): faces.append([ids[0],ids[i],ids[i+1]])
    v=np.asarray(vertices,np.float32); f=np.asarray(faces,np.int32)
    if v.ndim!=2 or v.shape[1]!=3 or f.ndim!=2 or f.shape[1]!=3 or not np.isfinite(v).all() or f.min()<0 or f.max()>=len(v):
        raise ValueError('OBJ 网格为空或包含无效坐标/索引。')
    return v,f
