"""Address relocation checks; no real device access or saved credentials."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("address_toolkit", ROOT / "tools/web-debug-toolkit.py")
toolkit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(toolkit)


class RelocationTests(unittest.TestCase):
    def setUp(self):
        self.profile = dict(id="b" * 64, pin="b" * 64, token="c" * 48, name="播放器", host="192.168.1.20", type="box")
        self.original = copy.deepcopy(self.profile)

    def test_verified_relocation_keeps_identity_and_pairing(self):
        with patch.object(toolkit, "native", return_value=(b'{"deviceType":"box"}', {})) as native:
            result = toolkit.relocate_profile(self.profile, "192.168.1.21")
        native.assert_called_once_with(result, "/status")
        self.assertEqual(self.profile, self.original)
        self.assertEqual(result, dict(self.original, host="192.168.1.21"))

    def test_bad_address_never_sends_credentials(self):
        with patch.object(toolkit, "native") as native:
            with self.assertRaises(ValueError):
                toolkit.relocate_profile(self.profile, "https://192.168.1.21:8766")
            native.assert_not_called()

    def test_failed_verification_keeps_old_profile(self):
        with patch.object(toolkit, "native", side_effect=ValueError("设备证书不匹配")):
            with self.assertRaisesRegex(ValueError, "证书不匹配"):
                toolkit.relocate_profile(self.profile, "192.168.1.21")
        self.assertEqual(self.profile, self.original)

    def test_discovery_filters_identity_and_verifies_tls(self):
        found = [dict(pin="a" * 64, host="192.168.1.22"), dict(pin=self.profile["pin"], host="192.168.1.21")]
        with patch.object(toolkit, "discover", return_value=found), patch.object(toolkit, "native", return_value=(b'{"deviceType":"box"}', {})) as native:
            result = toolkit.rediscover_profile(self.profile)
        self.assertEqual(result["host"], "192.168.1.21")
        self.assertEqual(native.call_count, 1)
        self.assertEqual(self.profile, self.original)

    def test_no_match_does_not_contact_other_device(self):
        with patch.object(toolkit, "discover", return_value=[dict(pin="a" * 64, host="192.168.1.22")]), patch.object(toolkit, "native") as native:
            with self.assertRaisesRegex(ValueError, "手动修改"):
                toolkit.rediscover_profile(self.profile)
            native.assert_not_called()

    def test_spoofed_discovery_does_not_replace_profile(self):
        with patch.object(toolkit, "discover", return_value=[dict(pin=self.profile["pin"], host="192.168.1.21")]), patch.object(toolkit, "native", side_effect=ValueError("设备证书不匹配")):
            with self.assertRaisesRegex(ValueError, "验证失败"):
                toolkit.rediscover_profile(self.profile)
        self.assertEqual(self.profile, self.original)

    def test_actual_android_address_parser(self):
        jdk = Path(os.environ.get("JAVA_HOME", ""))
        if not (jdk / "bin/javac.exe").exists():
            self.skipTest("JDK 17 required")
        probe = '''package cn.couger.livingplayer;
public class AddressProbe {public static void main(String[]args)throws Exception{
 String[][] valid={{"192.168.1.20","192.168.1.20"},{" 010.001.002.003 ","10.1.2.3"},{"172.16.0.1","172.16.0.1"},{"172.31.255.254","172.31.255.254"}};
 for(String[] pair:valid)if(!LanAddress.parse(pair[0]).equals(pair[1]))throw new AssertionError(pair[0]);
 for(String bad:new String[]{"","192.168.1.256","192.168.1.1:8766","https://192.168.1.1","127.0.0.1","8.8.8.8","172.32.0.1","192.168.1.1.","::1","-1.2.3.4"}){
  try{LanAddress.parse(bad);throw new AssertionError(bad);}catch(java.io.IOException expected){}
 }
}}'''
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "AddressProbe.java"
            source.write_text(probe, encoding="utf-8")
            subprocess.run([str(jdk / "bin/javac.exe"), "-encoding", "UTF-8", "-d", temp, str(ROOT / "work/android-player/src/cn/couger/livingplayer/LanAddress.java"), str(source)], check=True, capture_output=True)
            subprocess.run([str(jdk / "bin/java.exe"), "-cp", temp, "cn.couger.livingplayer.AddressProbe"], check=True, capture_output=True)


if __name__ == "__main__":
    unittest.main()
