import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from room_splat.headset import Device, REMOTE_ROOT
from room_splat.raw_scans import SESSION, RawSession, parse_frames, renumber, require_closed, unchanged, delete, delete_without_backup

class RawScanTests(unittest.TestCase):
    def test_reject_unsafe_or_duplicate_photos(self):
        for name in ('../photo.jpg', 'images/../../photo.jpg', 'images/other.jpg'):
            with self.assertRaises(ValueError):
                parse_frames(json.dumps(dict(file=name, sha256='a'*64)))
        frame = dict(file='images/frame_000001.jpg', sha256='a'*64)
        with self.assertRaises(ValueError):
            parse_frames(json.dumps(frame)+'\n'+json.dumps(frame))

    def test_session_scope(self):
        self.assertTrue(SESSION.fullmatch(REMOTE_ROOT+'/sessions/session-test'))
        for suffix in ('/sessions/../exports', '/sessions/session-test/child', '/exports/session-test'):
            self.assertFalse(SESSION.fullmatch(REMOTE_ROOT+suffix))

    def test_photo_delete_renumbers_without_overwriting_backup(self):
        with tempfile.TemporaryDirectory() as temp:
            capture=Path(temp)/'capture'; (capture/'images').mkdir(parents=True)
            (capture/'camera.json').write_text('{}')
            frames=[]
            for i in range(1,5):
                name=f'images/frame_{i:06d}.jpg'; data=bytes([i])
                (capture/name).write_bytes(data)
                frames.append(dict(file=name,sha256=hashlib.sha256(data).hexdigest()))
            result=renumber(capture,dict(frames=frames),'images/frame_000002.jpg')
            retained=parse_frames((result/'frames.jsonl').read_text())
            self.assertEqual([b'\x01',b'\x03',b'\x04'],[(result/f['file']).read_bytes() for f in retained])
            self.assertEqual(b'\x02',(capture/'images/frame_000002.jpg').read_bytes())
            self.assertEqual('images/frame_000004.jpg',frames[-1]['file'])
            self.assertFalse((result/'images/frame_000004.jpg').exists())

    def test_running_headset_blocks_mutation_before_backup(self):
        client=Mock(); client.shell.return_value='1234'
        with self.assertRaises(RuntimeError):
            delete(client,Mock(device=Device('p','device','PICO')),Path('unused'))
        client.execute.assert_not_called()

    def test_stale_snapshot_blocks_mutation(self):
        client=Mock(); original=Mock(fingerprint='old')
        with patch('room_splat.raw_scans.read_session', return_value=Mock(fingerprint='new')):
            with self.assertRaises(RuntimeError): unchanged(client,original)
        client.execute.assert_not_called()

    def test_backup_failure_blocks_mutation(self):
        client=Mock(); client.shell.return_value=''
        with patch('room_splat.raw_scans.unchanged'), patch('room_splat.raw_scans.download',side_effect=RuntimeError('corrupt')):
            with self.assertRaises(RuntimeError): delete(client,Mock(device=Mock()),Path('unused'))
        client.execute.assert_not_called()

    def test_direct_delete_never_downloads_and_only_targets_selected_session(self):
        device=Device('p','device','PICO')
        session=RawSession(device,REMOTE_ROOT+'/sessions/session-test',100,123,(), 'hash',False)
        client=Mock(); client.shell.return_value=''; client.adb='adb'
        with patch('room_splat.raw_scans.unchanged'), patch('room_splat.raw_scans.download') as download, patch('room_splat.headset.HeadsetClient.shell') as commit:
            delete_without_backup(client,session)
            download.assert_not_called(); client.execute.assert_not_called()
            command=commit.call_args.args[1]
            self.assertIn('mv '+session.path+' ',command)
            self.assertIn('readlink -f',command)
            self.assertNotIn('active-session.txt',command)
            self.assertNotIn('/exports',command)
            self.assertNotIn('rm -r -- '+session.path,command)

    def test_direct_delete_unsafe_path_and_running_scanner_rejected(self):
        client=Mock(); client.shell.return_value='123'
        session=RawSession(Device('p','device','PICO'),REMOTE_ROOT+'/sessions/session-test',100,123,(),'hash',False)
        with self.assertRaises(RuntimeError): delete_without_backup(client,session)
        unsafe=RawSession(session.device,REMOTE_ROOT+'/exports/room.zip',100,123,(),'hash',False)
        with self.assertRaises(ValueError): delete_without_backup(client,unsafe)
        client.execute.assert_not_called()

if __name__=='__main__': unittest.main()
