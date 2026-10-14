"""Exercise actual loopback HTTP/TLS boundaries without contacting a receiver."""
import datetime
import hashlib
import http.client
import importlib.util
import json
from pathlib import Path
import ssl
import tempfile
import threading
import unittest
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

spec = importlib.util.spec_from_file_location("security_toolkit", Path(__file__).with_name("web-debug-toolkit.py"))
toolkit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(toolkit)


class WebBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), toolkit.Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.close)
        self.host = "127.0.0.1:" + str(self.server.server_port)
        self.headers = {"Host": self.host, "Origin": "http://" + self.host, "X-Toolkit-Token": toolkit.CSRF}
        toolkit.PROFILES.clear()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def request(self, method="GET", path="/api/devices", headers=None, body=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        try:
            conn.request(method, path, body=body, headers=headers if headers is not None else self.headers)
            response = conn.getresponse()
            return response.status, response.read(), dict(response.getheaders())
        finally:
            conn.close()

    def test_wrong_host_rejected_even_with_valid_token(self):
        headers = dict(self.headers, Host="attacker.example")
        self.assertEqual(self.request(headers=headers)[0], 403)

    def test_missing_token_rejected(self):
        self.assertEqual(self.request(headers={"Host": self.host})[0], 403)

    def test_cross_origin_post_never_changes_profiles(self):
        headers = dict(self.headers, Origin="https://attacker.example")
        with patch.object(toolkit, "save") as save:
            self.assertEqual(self.request("POST", headers=headers, body='{"op":"forget","id":"missing"}')[0], 403)
            save.assert_not_called()

    def test_profile_list_does_not_disclose_credentials(self):
        toolkit.PROFILES["test"] = dict(id="test", name="Example", host="192.168.1.2", token="private-token", pin="private-pin")
        status, data, headers = self.request()
        self.assertEqual(status, 200)
        self.assertNotIn(b"private-token", data)
        self.assertNotIn(b"private-pin", data)
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertEqual(headers["X-Frame-Options"], "DENY")

    def test_configuration_changes_rejected_before_receiver_contact(self):
        toolkit.PROFILES["test"] = dict(id="test", token="test")
        with patch.object(toolkit, "native") as native:
            self.assertEqual(self.request("POST", "/api/config?device=test", body="oneTapKey=KEY_POWER")[0], 403)
            native.assert_not_called()

    def test_large_post_rejected_before_reading_body(self):
        headers = dict(self.headers, **{"Content-Length": "8193"})
        self.assertEqual(self.request("POST", headers=headers, body="")[0], 400)


class PinBoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Local Test Receiver")])
        now = datetime.datetime.now(datetime.timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
                .serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=1))
                .not_valid_after(now+datetime.timedelta(days=1)).sign(key, hashes.SHA256()))
        cls.pin = hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()
        directory = Path(cls.temp.name)
        (directory / "cert.pem").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        (directory / "key.pem").write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(directory / "cert.pem", directory / "key.pem")
        cls.requests = []

        class Receiver(BaseHTTPRequestHandler):
            def handle(self):
                try:
                    super().handle()
                except ConnectionResetError:
                    # A pin mismatch deliberately closes TLS before sending HTTP.
                    pass

            def log_message(self, *args):
                pass

            def do_GET(self):
                cls.requests.append(self.headers.get("Authorization"))
                self.send_response(200)
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.write(b"{}")

        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Receiver)
        cls.server.socket = context.wrap_socket(cls.server.socket, server_side=True)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(2)
        cls.temp.cleanup()

    def contact(self, pin):
        # Keep real TLS and HTTP; substitute only the hard-coded receiver port.
        connection_init = http.client.HTTPSConnection.__init__
        def local_connection(instance, host, port, **kwargs):
            connection_init(instance, "127.0.0.1", self.server.server_port, **kwargs)
        with patch.object(toolkit.http.client.HTTPSConnection, "__init__", new=local_connection):
            return toolkit.native(dict(host="127.0.0.1", pin=pin, token="test-secret"), "/status")

    def test_wrong_certificate_never_receives_bearer(self):
        before = len(self.requests)
        with self.assertRaisesRegex(ValueError, "证书不匹配"):
            self.contact("0" * 64)
        self.assertEqual(len(self.requests), before)

    def test_matching_certificate_receives_bearer(self):
        data, _ = self.contact(self.pin)
        self.assertEqual(json.loads(data), {})
        self.assertEqual(self.requests[-1], "Bearer test-secret")


if __name__ == "__main__":
    unittest.main()
