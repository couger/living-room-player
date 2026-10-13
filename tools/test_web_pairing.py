"""Protocol tests with real RSA and a simulated phone/box receiver; no device access."""
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

spec = importlib.util.spec_from_file_location("toolkit", Path(__file__).with_name("web-debug-toolkit.py"))
toolkit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(toolkit)


class PairTests(unittest.TestCase):
    def setUp(self):
        toolkit.PAIRINGS.clear()
        toolkit.PROFILES.clear()
        self.pin = "b" * 64
        self.token = "c" * 48
        self.request = None
        self.state = "pending"
        self.device_type = "box"
        self.saved = []
        self.native_patch = patch.object(toolkit, "native", side_effect=self.receiver)
        self.save_patch = patch.object(toolkit, "save", side_effect=lambda: self.saved.append(json.dumps(toolkit.PROFILES)))
        self.native_patch.start()
        self.save_patch.start()
        self.addCleanup(self.native_patch.stop)
        self.addCleanup(self.save_patch.stop)

    def receiver(self, profile, path, payload=None):
        if path == "/pair/request":
            self.request = payload
            result = {"id": "request", "expires": 0}
        elif path == "/pair/cancel":
            self.state = "expired"
            result = {"ok": True}
        elif path == "/pair/result":
            result = {"state": self.state}
            if self.state == "approved":
                public = serialization.load_der_public_key(base64.b64decode(self.request["key"]))
                data = json.dumps({"token": self.token, "fingerprint": self.pin}).encode()
                result["cipher"] = base64.b64encode(public.encrypt(data, padding.OAEP(mgf=padding.MGF1(hashes.SHA1()), algorithm=hashes.SHA1(), label=None))).decode()
        elif path == "/status":
            self.assertEqual(profile["token"], self.token)
            result = {"deviceType": self.device_type}
        else:
            raise AssertionError(path)
        return json.dumps(result).encode(), {}

    def begin(self):
        return toolkit.pairing(dict(op="begin", host="192.168.1.2", pin=self.pin, name="客厅播放器"))

    def test_phone_and_box_approval_and_identity_code(self):
        for device_type in ("phone", "box"):
            toolkit.PAIRINGS.clear()
            self.device_type = device_type
            pending = self.begin()
            digest = hashlib.sha256((self.pin + "|" + self.request["key"] + "|" + self.request["nonce"]).encode()).digest()
            self.assertEqual(pending["code"], "%06d" % (int.from_bytes(digest[:4], "big") % 1000000))
            self.assertNotIn("token", pending)
            self.assertEqual(toolkit.pairing(dict(op="poll", id=pending["id"]))["state"], "pending")
            self.state = "approved"
            result = toolkit.pairing(dict(op="poll", id=pending["id"]))
            self.assertEqual(result, {"state": "approved", "device": self.pin})
            self.assertEqual(toolkit.PROFILES[self.pin]["type"], device_type)
            self.assertEqual(toolkit.PROFILES[self.pin]["token"], self.token)
            self.assertNotIn("key", toolkit.PAIRINGS[pending["id"]])
            self.assertEqual(toolkit.pairing(dict(op="poll", id=pending["id"])), result)
            self.state = "pending"

    def test_cancel_deny_and_expire_do_not_save(self):
        for action in ("cancel", "denied", "expired"):
            self.state = "pending"
            pending = self.begin()
            if action == "cancel":
                result = toolkit.pairing(dict(op="cancel", id=pending["id"]))
                self.assertEqual(result["state"], "cancelled")
            else:
                self.state = action
                self.assertEqual(toolkit.pairing(dict(op="poll", id=pending["id"]))["state"], action)
            self.assertFalse(toolkit.PROFILES)
            self.assertNotIn(pending["id"], toolkit.PAIRINGS)
        pending = self.begin()
        toolkit.PAIRINGS[pending["id"]]["until"] = 0
        self.assertEqual(toolkit.pairing(dict(op="poll", id=pending["id"]))["state"], "expired")
        self.assertFalse(self.saved)

    def test_reloaded_browser_replaces_own_pending_request(self):
        first = self.begin()
        second = self.begin()
        self.assertNotEqual(first["id"], second["id"])
        self.assertNotIn(first["id"], toolkit.PAIRINGS)
        self.assertEqual(len(toolkit.PAIRINGS), 1)

    def test_certificate_mismatch_does_not_save(self):
        pending = self.begin()
        self.pin = "d" * 64
        self.state = "approved"
        with self.assertRaises(ValueError):
            toolkit.pairing(dict(op="poll", id=pending["id"]))
        self.assertFalse(toolkit.PROFILES)

    def test_reject_non_lan_target(self):
        with self.assertRaises(ValueError):
            toolkit.pairing(dict(op="begin", host="8.8.8.8", pin=self.pin))
        self.assertIsNone(self.request)


if __name__ == "__main__":
    unittest.main()
