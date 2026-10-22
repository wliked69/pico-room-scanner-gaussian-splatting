"""Interactive in-app preview; simplification affects display only, never the OBJ."""
import math
import numpy as np
import tkinter as tk
from tkinter import ttk
from .collision import read_obj

def preview_geometry(vertices,faces,budget=6000):
    if len(faces)<=budget: return vertices,faces
    low=vertices.min(axis=0); extent=float(np.ptp(vertices,axis=0).max())
    for resolution in (48,32,24,16,12,8):
        bins=np.floor((vertices-low)/max(extent,1e-9)*resolution).astype(np.int32)
        _,inverse=np.unique(bins,axis=0,return_inverse=True)
        v=np.zeros((inverse.max()+1,3),np.float64); np.add.at(v,inverse,vertices)
        v/=np.bincount(inverse)[:,None]
        f=inverse[faces]; f=f[(f[:,0]!=f[:,1])&(f[:,1]!=f[:,2])&(f[:,2]!=f[:,0])]
        _,ids=np.unique(np.sort(f,axis=1),axis=0,return_index=True); f=f[ids]
        if len(f)<=budget: return v.astype(np.float32),f
    return v.astype(np.float32),f

class MeshPreview:
    def __init__(self,parent,path):
        vertices,faces=read_obj(path)
        self.window=tk.Toplevel(parent); self.window.title('碰撞网格预览 · '+path.name)
        self.window.geometry('1000x760'); self.window.minsize(700,480)
        self.window.transient(parent)
        self.pending=None; self.closed=False; self.anchor=None
        self.vertices,self.faces=preview_geometry(vertices,faces)
        center=(vertices.min(axis=0)+vertices.max(axis=0))/2
        self.vertices=(self.vertices-center)/max(float(np.ptp(vertices,axis=0).max()),1e-9)
        outer=ttk.Frame(self.window,padding=12); outer.pack(fill='both',expand=True)
        ttk.Label(outer,text=f'{len(vertices):,} 顶点 · {len(faces):,} 三角形 · 预览简化为 {len(self.faces):,} 三角形',wraplength=850).pack(anchor='w')
        ttk.Label(outer,text='左键拖动旋转 · 滚轮缩放 · 右键拖动平移 · R 恢复视角；显示简化不影响导出的网格').pack(anchor='w',pady=4)
        controls=ttk.Frame(outer); controls.pack(fill='x',pady=6)
        self.wire=tk.BooleanVar(value=False); self.cut=tk.BooleanVar(value=False)
        ttk.Checkbutton(controls,text='线框',variable=self.wire,command=self.schedule).pack(side='left')
        ttk.Checkbutton(controls,text='剖开前侧',variable=self.cut,command=self.schedule).pack(side='left',padx=12)
        ttk.Button(controls,text='恢复视角',command=self.reset).pack(side='left')
        self.canvas=tk.Canvas(outer,bg='#142130',highlightthickness=0); self.canvas.pack(fill='both',expand=True)
        self.canvas.bind('<Configure>',lambda _:self.schedule())
        self.canvas.bind('<ButtonPress-1>',self.begin); self.canvas.bind('<ButtonPress-3>',self.begin)
        self.canvas.bind('<B1-Motion>',self.rotate); self.canvas.bind('<B3-Motion>',self.pan)
        self.canvas.bind('<MouseWheel>',self.zoom)
        self.window.bind('r',lambda _:self.reset()); self.window.protocol('WM_DELETE_WINDOW',self.close)
        self.reset()

    def reset(self):
        self.yaw=-.45; self.pitch=.28; self.scale=1.; self.offset=np.zeros(2); self.schedule()
    def begin(self,event): self.anchor=(event.x,event.y)
    def rotate(self,event):
        if self.anchor:
            x,y=self.anchor; self.yaw+=(event.x-x)*.007; self.pitch=np.clip(self.pitch+(event.y-y)*.007,-1.5,1.5)
            self.anchor=(event.x,event.y); self.schedule()
    def pan(self,event):
        if self.anchor:
            self.offset+=np.array([event.x-self.anchor[0],event.y-self.anchor[1]])
            self.anchor=(event.x,event.y); self.schedule()
    def zoom(self,event):
        self.scale=np.clip(self.scale*math.exp(event.delta/120*.12),.1,12); self.schedule()
    def schedule(self):
        if not self.closed and self.pending is None: self.pending=self.window.after(25,self.draw)
    def draw(self):
        self.pending=None
        cy,sy=math.cos(self.yaw),math.sin(self.yaw); cp,sp=math.cos(self.pitch),math.sin(self.pitch)
        rotation=np.array([[cy,0,sy],[sp*sy,cp,-sp*cy],[-cp*sy,sp,cp*cy]])
        v=self.vertices@rotation.T; tri=v[self.faces]; depths=tri[:,:,2].mean(axis=1)
        width,height=self.canvas.winfo_width(),self.canvas.winfo_height()
        p=v[:,:2]*[1,-1]*min(width,height)*.8*self.scale+np.array([width/2,height/2])+self.offset
        normals=np.cross(tri[:,1]-tri[:,0],tri[:,2]-tri[:,0]); norm=np.linalg.norm(normals,axis=1)
        shade=np.abs(normals[:,2])/np.maximum(norm,1e-9)
        cutoff=np.quantile(depths,.7) if self.cut.get() else float('inf')
        self.canvas.delete('all')
        for i in np.argsort(depths):
            if depths[i]>cutoff: continue
            coords=p[self.faces[i]].ravel().tolist()
            light=float(shade[i]); color='#%02x%02x%02x'%(int(45+40*light),int(100+75*light),int(135+90*light))
            self.canvas.create_polygon(coords,fill='' if self.wire.get() else color,outline='#86bdcc' if self.wire.get() else color,width=1)
        self.canvas.create_text(12,12,anchor='nw',fill='#d8e9ef',text='近似碰撞表面 · 原模型坐标')
    def close(self):
        self.closed=True
        if self.pending is not None: self.window.after_cancel(self.pending)
        self.window.destroy()
