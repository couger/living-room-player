"""Verify real loopback lifecycle and duplicate-instance identity without devices."""
import http.client
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("desktop_toolkit", Path(__file__).with_name("web_desktop.py"))
desktop = importlib.util.module_from_spec(spec)
spec.loader.exec_module(desktop)


class DesktopToolkitTests(unittest.TestCase):
    def test_frozen_copy_reuses_project_profiles_and_portable_copy_is_separate(self):
        with tempfile.TemporaryDirectory() as folder:
            project = Path(folder) / "project"
            (project / "tools").mkdir(parents=True)
            (project / "tools/web-debug-toolkit.py").touch()
            (project / "work/player-control").mkdir(parents=True)
            (project / "work/player-control/index.html").touch()
            exe = project / "outputs/PC-WEB-EXE/LivingRoomWeb/LivingRoomWeb.exe"
            with patch.object(desktop.sys, "frozen", True, create=True), patch.object(desktop.sys, "executable", str(exe)):
                self.assertEqual(desktop.default_store(), project / "local/web-toolkit/devices.local.json")
            portable = Path(folder) / "portable/LivingRoomWeb.exe"
            with patch.object(desktop.sys, "frozen", True, create=True), patch.object(desktop.sys, "executable", str(portable)):
                self.assertEqual(desktop.default_store(), portable.parent / "用户数据/devices.local.json")

    def test_packaged_flow_serves_page_and_checks_crypto_without_devices(self):
        with tempfile.TemporaryDirectory() as folder:
            store = Path(folder) / "devices.local.json"
            result = desktop.self_test(store)
            self.assertTrue(result["ok"])
            self.assertFalse(store.exists())

    def test_duplicate_identity_port_conflict_and_shutdown(self):
        with tempfile.TemporaryDirectory() as folder:
            store = Path(folder) / "devices.local.json"
            store.write_text(json.dumps({"demo": {"token": "synthetic-private-token"}}), encoding="utf-8")
            server = desktop.toolkit.create_server(0, store)
            server.desktop = True
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            port = server.server_port
            try:
                self.assertTrue(desktop.existing_desktop(port, store))
                self.assertFalse(desktop.existing_desktop(port, Path(folder) / "other.json"))
                conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                conn.request("GET", "/toolkit-health")
                response = conn.getresponse()
                self.assertNotIn(b"synthetic-private-token", response.read())
                conn.close()
                conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                conn.request("GET", "/toolkit-health", headers={"Host": "attacker.example"})
                self.assertEqual(conn.getresponse().status, 403)
                conn.close()
                with self.assertRaises(OSError):
                    desktop.toolkit.create_server(port, store)
            finally:
                server.shutdown()
                server.server_close()
                worker.join(3)
            self.assertFalse(desktop.existing_desktop(port, store))
            replacement = desktop.toolkit.create_server(port, store)
            replacement.server_close()


if __name__ == "__main__":
    unittest.main()
