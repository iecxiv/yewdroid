import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("download_queue", ROOT / "app/src/main/python/android_download_queue.py")
queue = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(queue)


class Bridge:
    stop = False
    def cancelled(self):
        return self.stop
    def update(self, *args):
        pass


class QueueTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = self.temp.name
        self.bridge = Bridge()
    def tearDown(self):
        self.temp.cleanup()
    def add(self, *ids):
        return queue.enqueue(self.root, json.dumps([
            {"ytid": item, "title": item, "folder": self.root, "audio": True} for item in ids]))
    def state(self, video):
        with queue.database(self.root) as db:
            row = db.execute("SELECT state,attempts FROM downloads WHERE ytid=?", (video,)).fetchone()
        return tuple(row)
    def test_completed_not_repeated(self):
        self.add("aaaaaaaaaaa", "bbbbbbbbbbb")
        with patch.object(queue, "_download", return_value="Download/YewTube/test.m4a") as download:
            self.assertEqual(queue.run(self.root, self.bridge), "done")
            queue.run(self.root, self.bridge)
            self.assertEqual(download.call_count, 2)
    def test_limited_network_retries(self):
        self.add("aaaaaaaaaaa")
        with patch.object(queue, "_download", side_effect=RuntimeError("Failed to resolve host")):
            self.assertEqual(queue.run(self.root, self.bridge), "retry")
            self.assertEqual(queue.run(self.root, self.bridge), "retry")
            self.assertEqual(queue.run(self.root, self.bridge), "done")
        self.assertEqual(self.state("aaaaaaaaaaa"), ("failed", 3))
    def test_restart_reclaims_running(self):
        self.add("aaaaaaaaaaa")
        queue.claim(self.root)
        with patch.object(queue, "_download", return_value="saved"):
            queue.run(self.root, self.bridge)
        self.assertEqual(self.state("aaaaaaaaaaa")[0], "done")
    def test_cancel_resume(self):
        self.add("aaaaaaaaaaa")
        queue.cancel(self.root)
        self.assertEqual(self.state("aaaaaaaaaaa")[0], "cancelled")
        queue.reset(self.root, "cancelled")
        with patch.object(queue, "_download", return_value="saved"):
            queue.run(self.root, self.bridge)
        self.assertEqual(self.state("aaaaaaaaaaa")[0], "done")
    def test_system_stop_keeps_pending(self):
        self.add("aaaaaaaaaaa")
        def stop(job, workspace, progress):
            self.bridge.stop = True
            progress({})
        with patch.object(queue, "_download", side_effect=stop):
            self.assertEqual(queue.run(self.root, self.bridge), "stopped")
        self.assertEqual(self.state("aaaaaaaaaaa")[0], "pending")
    def test_failed_track_does_not_abort_queue(self):
        self.add("aaaaaaaaaaa", "bbbbbbbbbbb")
        def download(job, *args):
            if job["ytid"] == "aaaaaaaaaaa":
                raise RuntimeError("This video is not available")
            return "saved"
        with patch.object(queue, "_download", side_effect=download):
            queue.run(self.root, self.bridge)
        self.assertEqual(self.state("aaaaaaaaaaa")[0], "failed")
        self.assertEqual(self.state("bbbbbbbbbbb")[0], "done")
    def test_validation_is_atomic(self):
        with self.assertRaises(ValueError):
            self.add("aaaaaaaaaaa", "bad")
        with queue.database(self.root) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM downloads").fetchone()[0], 0)


if __name__ == "__main__":
    unittest.main()
