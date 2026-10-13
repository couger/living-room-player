"""Local-only multi-device debug UI. Native TLS credentials never enter browser storage."""
import argparse
import base64
import hashlib
import http.client
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import socket
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parents[1]
STORE = ROOT / "local" / "web-toolkit" / "devices.local.json"
LOCK = threading.RLock()
PROFILES = {}
CSRF = secrets.token_urlsafe(32)
PAIRINGS = {}
PAIR_LOCK = threading.RLock()


def local_address(host):
    address = ipaddress.ip_address(host)
    if address.version != 4 or not any(address in ipaddress.ip_network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")):
        raise ValueError("仅支持家庭局域网 IPv4 地址")
    return str(address)


def discover():
    broadcasts = {"255.255.255.255"}
    if os.name == "nt":
        import subprocess
        ps = str(Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32/WindowsPowerShell/v1.0/powershell.exe")
        try:
            interfaces = json.loads(subprocess.check_output([ps, "-NoProfile", "-Command", "@(Get-NetIPAddress -AddressFamily IPv4 | Select-Object IPAddress,PrefixLength) | ConvertTo-Json -Compress"], timeout=8, text=True))
            for interface in interfaces:
                try:
                    host = local_address(interface["IPAddress"])
                    network = ipaddress.ip_network(host + "/" + str(interface["PrefixLength"]), strict=False)
                    if network.prefixlen <= 30:
                        broadcasts.add(str(network.broadcast_address))
                except ValueError:
                    continue
        except (ValueError, OSError, subprocess.SubprocessError):
            pass
    found = {}
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        udp.settimeout(0.5)
        for host in broadcasts:
            try:
                udp.sendto(b"LIVING_PLAYER_DISCOVER_1", (host, 8767))
            except OSError:
                pass
        until = time.monotonic() + 3
        while time.monotonic() < until:
            try:
                data, peer = udp.recvfrom(2048)
                host = local_address(peer[0])
                result = json.loads(data)
                pin = result.get("fingerprint", "")
                if re.fullmatch(r"[a-f0-9]{64}", pin):
                    found[pin] = dict(host=host, pin=pin, name=str(result.get("name", "播放器"))[:80])
            except (OSError, ValueError, TypeError):
                pass
    return list(found.values())


def pairing(payload):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding, rsa
    with PAIR_LOCK:
        now = time.monotonic()
        for key in list(PAIRINGS):
            if PAIRINGS[key]["until"] < now:
                del PAIRINGS[key]
        op = payload.get("op")
        if op == "begin":
            if len(PAIRINGS) >= 4:
                raise ValueError("正在等待的配对较多，请先取消或稍后再试")
            host = local_address(payload["host"])
            pin = payload["pin"]
            if not re.fullmatch(r"[a-f0-9]{64}", pin):
                raise ValueError("设备身份格式错误，请重新搜索")
            # Recover this PC's own pending request after a browser reload.
            for previous_id in list(PAIRINGS):
                previous = PAIRINGS[previous_id]
                if previous["profile"]["pin"] == pin and "done" not in previous:
                    native(previous["profile"], "/pair/cancel", dict(id=previous["remote"]))
                    del PAIRINGS[previous_id]
            key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
            public = base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
            nonce = secrets.token_hex(16)
            code = "%06d" % (int.from_bytes(hashlib.sha256((pin + "|" + public + "|" + nonce).encode()).digest()[:4], "big") % 1000000)
            profile = dict(host=host, pin=pin, id=pin, token="0" * 48)
            request = json.loads(native(profile, "/pair/request", dict(key=public, nonce=nonce, name="PC WEB 调试工具"))[0])
            local_id = secrets.token_urlsafe(24)
            PAIRINGS[local_id] = dict(profile=profile, key=key, remote=request["id"], until=now + 180, name=str(payload.get("name") or "播放器")[:40])
            return dict(id=local_id, code=code, state="pending")
        session_id = payload.get("id", "")
        session = PAIRINGS.get(session_id)
        if session is None:
            return dict(state="expired")
        if op == "cancel":
            del PAIRINGS[session_id]
            if "done" not in session:
                native(session["profile"], "/pair/cancel", dict(id=session["remote"]))
            return dict(state="cancelled")
        if op != "poll":
            raise ValueError("未知配对操作")
        if "done" in session:
            return session["done"]
        result = json.loads(native(session["profile"], "/pair/result", dict(id=session["remote"]))[0])
        state = result.get("state")
        if state in {"expired", "denied"}:
            del PAIRINGS[session_id]
        if state != "approved":
            return dict(state=state)
        secret = json.loads(session["key"].decrypt(base64.b64decode(result["cipher"]), padding.OAEP(mgf=padding.MGF1(hashes.SHA1()), algorithm=hashes.SHA1(), label=None)))
        profile = dict(session["profile"], token=secret["token"])
        if secret.get("fingerprint") != profile["pin"] or not re.fullmatch(r"[a-f0-9]{48}", profile["token"]):
            raise ValueError("配对设备身份或授权异常，请重新配对")
        status = json.loads(native(profile, "/status")[0])
        profile.update(name=session["name"], type=status.get("deviceType", "phone"))
        with LOCK:
            PROFILES[profile["id"]] = profile
            save()
        session["done"] = dict(state="approved", device=profile["id"])
        session.pop("key")
        return session["done"]


def decode_code(code):
    if not code.startswith("LP1:") or len(code) > 4096:
        raise ValueError("需要有效的备用连接码 LP1:…")
    parts = base64.urlsafe_b64decode(code[4:] + "=" * (-len(code[4:]) % 4)).decode().split("|")
    if len(parts) != 3:
        raise ValueError("连接码格式错误")
    host, token, pin = parts
    local_address(host)
    if not re.fullmatch(r"[a-f0-9]{48}", token) or not re.fullmatch(r"[a-f0-9]{64}", pin):
        raise ValueError("连接凭据不完整")
    return dict(id=pin, host=host, token=token, pin=pin)


def relocate_profile(profile, host):
    candidate = dict(profile, host=local_address(host))
    state = json.loads(native(candidate, "/status")[0])
    candidate["type"] = state.get("deviceType", "phone")
    return candidate


def rediscover_profile(profile):
    error = None
    for found in discover():
        if found["pin"] == profile["pin"]:
            try:
                return relocate_profile(profile, found["host"])
            except (ValueError, OSError) as exc:
                error = exc
    if error is not None:
        raise ValueError("找到了设备但验证失败：" + str(error))
    raise ValueError("未找到此设备，请检查同一网络与受控端发现开关，或手动修改 IP 地址")


def save():
    STORE.parent.mkdir(parents=True, exist_ok=True)
    if os.name == "nt":
        import subprocess
        sid = json.loads(subprocess.check_output([str(Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32/WindowsPowerShell/v1.0/powershell.exe"), "-NoProfile", "-Command", "[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value | ConvertTo-Json"], text=True))
        subprocess.run(["icacls", str(STORE.parent), "/inheritance:r", "/grant:r", "*" + sid + ":(OI)(CI)F", "*S-1-5-18:(OI)(CI)F"], check=True, capture_output=True)
    else:
        STORE.parent.chmod(0o700)
    temp = STORE.with_suffix(".tmp")
    with os.fdopen(os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w", encoding="utf-8") as f:
        json.dump(PROFILES, f, ensure_ascii=False)
    os.replace(temp, STORE)


def native(profile, path, payload=None):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE  # Replaced by exact SHA-256 pin before sending credentials.
    conn = http.client.HTTPSConnection(profile["host"], 8766, timeout=12, context=context)
    try:
        conn.connect()
        if not secrets.compare_digest(hashlib.sha256(conn.sock.getpeercert(binary_form=True)).hexdigest(), profile["pin"]):
            raise ValueError("设备证书不匹配，拒绝发送凭据")
        body = json.dumps(payload).encode() if payload is not None else None
        conn.request("POST" if body is not None else "GET", path, body,
                     {"Authorization": "Bearer " + profile["token"], "Content-Type": "application/json"})
        response = conn.getresponse()
        data = response.read(4 * 1024 * 1024 + 1)
        if len(data) > 4 * 1024 * 1024:
            raise ValueError("设备响应过大")
        if response.status != 200:
            hint = ""
            try:
                hint = json.loads(data).get("error", "")
            except (ValueError, AttributeError):
                pass
            raise ValueError("设备拒绝请求：" + str(response.status) + (" · " + str(hint)[:200] if hint else ""))
        return data, dict(response.getheaders())
    finally:
        conn.close()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # Never log posted credentials or device identifiers.

    def reply(self, data, mime="application/json", status=200, headers=None):
        if not isinstance(data, bytes):
            data = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()

    def handle_request(self):
        try:
            origin = "http://127.0.0.1:" + str(self.server.server_port)
            if self.headers.get("Host") != origin[7:]:
                return self.reply({"error": "仅允许本机入口"}, status=403)
            route = urlsplit(self.path)
            if route.path == "/" and self.command == "GET":
                page = (ROOT / "work/player-control/index.html").read_text(encoding="utf-8")
                page = page.replace("<script>", "<script>window.toolkitToken=" + json.dumps(CSRF) + ";", 1)
                return self.reply(page.encode(), "text/html; charset=utf-8")
            if self.headers.get("X-Toolkit-Token") != CSRF:
                return self.reply({"error": "请重新打开本地工具包页面"}, status=403)
            payload = None
            if self.command == "POST":
                if self.headers.get("Origin") != origin:
                    return self.reply({"error": "来源不匹配"}, status=403)
                size = int(self.headers.get("Content-Length", "0"))
                if size < 1 or size > 8192:
                    raise ValueError("请求大小错误")
                body = self.rfile.read(size).decode()
                payload = json.loads(body) if route.path in {"/api/devices", "/api/pair"} else {k: v[0] for k, v in parse_qs(body).items()}
            if route.path == "/api/discover" and self.command == "GET":
                return self.reply(discover())
            if route.path == "/api/pair" and payload is not None:
                try:
                    return self.reply(pairing(payload))
                except ImportError:
                    raise ValueError("PC 缺少配对组件，请运行 python -m pip install -r tools/web-requirements.txt")
            if route.path == "/api/devices":
                with LOCK:
                    if payload is not None:
                        operation = payload.get("op")
                        if operation == "add":
                            p = decode_code(payload["code"].strip())
                            state = json.loads(native(p, "/status")[0])
                            old = PROFILES.get(p["id"], {})
                            p.update(name=str(payload.get("name") or old.get("name") or "播放器")[:40], type=state.get("deviceType", "phone"))
                            PROFILES[p["id"]] = p
                        elif operation == "forget":
                            PROFILES.pop(payload["id"], None)
                        elif operation == "rename":
                            name = str(payload["name"]).strip()
                            if not name or len(name) > 40:
                                raise ValueError("请输入 1—40 字的设备名称")
                            PROFILES[payload["id"]]["name"] = name
                        elif operation == "address":
                            PROFILES[payload["id"]] = relocate_profile(PROFILES[payload["id"]], payload["host"])
                        elif operation == "rediscover":
                            PROFILES[payload["id"]] = rediscover_profile(PROFILES[payload["id"]])
                        else:
                            raise ValueError("未知管理操作")
                        save()
                    return self.reply([dict(id=p["id"], name=p["name"], type=p.get("type"), host=p["host"]) for p in PROFILES.values()])
            device_id = parse_qs(route.query).get("device", [""])[0]
            with LOCK:
                p = dict(PROFILES[device_id])
            if route.path == "/api/config" and payload is not None:
                return self.reply({"error": "WEB 仅显示绑定结果，请在被控端本机设置"}, status=403)
            if route.path == "/cgi-bin/media-status.sh" and self.command == "GET":
                data, _ = native(p, "/media-status")
                return self.reply(data)
            if route.path == "/cgi-bin/status.sh" and self.command == "GET":
                data, _ = native(p, "/status")
                return self.reply(data)
            if route.path == "/cgi-bin/screen.sh" and self.command == "GET":
                data, headers = native(p, "/frame")
                return self.reply(data, "image/jpeg", headers={k: v for k, v in headers.items() if k.lower().startswith("x-display-")})
            if route.path == "/cgi-bin/action.sh" and payload is not None:
                original_payload = dict(payload)
                action = payload.get("action")
                if action in {"capture_remote_key", "inspect_keys_start", "inspect_keys_stop"}:
                    return self.reply({"error": "按键识别及绑定只能在被控端本机操作"}, status=403)
                if action not in {"wake", "sleep", "display_off", "back", "home", "volume_up", "volume_down", "play", "pause", "previous", "next", "one_tap", "media_toggle", "tap", "long_press", "swipe", "swipe_up", "swipe_down", "swipe_left", "swipe_right"}:
                    raise ValueError("本工具包仅提供屏幕和按键控制")
                if action.startswith("swipe_"):
                    state = json.loads(native(p, "/status")[0])
                    w, h = state["width"], state["height"]
                    points = {"swipe_left": (3*w//4, h//2, w//4, h//2), "swipe_right": (w//4, h//2, 3*w//4, h//2), "swipe_up": (w//2, 3*h//4, w//2, h//4), "swipe_down": (w//2, h//4, w//2, 3*h//4)}
                    payload = dict(zip(("x", "y", "x2", "y2"), points[action]), action="swipe")
                else:
                    payload = {k: int(v) if k in {"x", "y", "x2", "y2"} else v for k, v in payload.items() if k in {"action", "x", "y", "x2", "y2"}}
                if action == "media_toggle":
                    # Explicit desired state prevents a stale icon from reversing intent.
                    if "desiredState" in original_payload:
                        if original_payload["desiredState"] not in {"playing", "paused"}:
                            raise ValueError("无效的播放意图")
                        payload["desiredState"] = original_payload["desiredState"]
                    if "audioOnly" in original_payload:
                        if original_payload["audioOnly"] not in {"0", "1"}:
                            raise ValueError("无效的 HDMI 模式")
                        payload["audioOnly"] = original_payload["audioOnly"] == "1"
                result, _ = native(p, "/action", payload)
                if action == "media_toggle":
                    return self.reply(result)
                return self.reply(b"OK", "text/plain")
            self.reply({"error": "不存在此接口"}, status=404)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass
        except Exception as error:
            self.reply({"error": str(error) if isinstance(error, ValueError) else "连接失败，请检查设备、地址与授权"}, status=400)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8760)
    args = parser.parse_args()
    if STORE.exists():
        PROFILES.update(json.loads(STORE.read_text(encoding="utf-8")))
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    print(f"本机调试工具：http://127.0.0.1:{server.server_port} ；关闭本进程即停止工具包。", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.server_close()
