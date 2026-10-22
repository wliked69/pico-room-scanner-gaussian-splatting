import tempfile
import unittest
from pathlib import Path
import numpy as np
from room_splat.collision import export_collision, read_obj, read_splats, reconstruct_shell

def fixture(path):
    rng=np.random.default_rng(123)
    points=rng.normal(size=(12000,3)); points/=np.linalg.norm(points,axis=1)[:,None]
    data=np.empty(len(points),dtype=[('x','<f4'),('y','<f4'),('z','<f4'),('opacity','<f4')])
    for i,name in enumerate(('x','y','z')): data[name]=points[:,i]
    data['opacity']=5
    with path.open('wb') as file:
        file.write(('ply\nformat binary_little_endian 1.0\nelement vertex '+str(len(data))+'\nproperty float x\nproperty float y\nproperty float z\nproperty float opacity\nend_header\n').encode())
        data.tofile(file)

class CollisionTests(unittest.TestCase):
    def test_closed_shell_valid_indices_coordinates_and_original_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'sphere.ply'; fixture(path); before=path.read_bytes()
            obj=export_collision(path,quality='快速',log=lambda _:None)
            v,f=read_obj(obj)
            self.assertTrue(np.isfinite(v).all()); self.assertGreater(len(f),100)
            self.assertLess(np.abs(v).max(),1.15)
            edges=np.sort(np.concatenate([f[:,[0,1]],f[:,[1,2]],f[:,[2,0]]]),axis=1)
            _,counts=np.unique(edges,axis=0,return_counts=True)
            self.assertTrue((counts==2).all())
            self.assertEqual(before,path.read_bytes())
            self.assertTrue((obj.parent/'collision-report.json').is_file())
            self.assertTrue((obj.parent/'Unity使用说明.txt').is_file())

    def test_invalid_ply_and_empty_cloud_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'broken.ply';path.write_bytes(b'ply\nformat binary_little_endian 1.0\n')
            with self.assertRaises(ValueError): read_splats(path)
        with self.assertRaises(ValueError): reconstruct_shell(np.zeros((10,3)),np.ones(10))

    def test_cancelled_export_never_changes_source(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'sphere.ply'; fixture(path); before=path.read_bytes()
            def check(): raise RuntimeError('cancelled')
            with self.assertRaisesRegex(RuntimeError,'cancelled'):export_collision(path,check=check)
            self.assertEqual(before,path.read_bytes())
            self.assertEqual([],list(Path(temp).glob('collision-*')))

    def test_obj_invalid_index_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'bad.obj';path.write_text('v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 99\n')
            with self.assertRaises(ValueError):read_obj(path)

if __name__=='__main__': unittest.main()
