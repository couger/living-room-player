"""Windowed desktop host for the same loopback WEB toolkit; no device commands at startup."""
import argparse
import hashlib
import http.client
import importlib
import json
import os
from pathlib import Path
import sys
import threading
import tkinter as tk
from tkinter import messagebox
import webbrowser

toolkit = importlib.import_module("web-debug-toolkit")


def default_store():
    if not getattr(sys, "frozen", False):
        return Path(__file__).resolve().parents[1] / "local/web-toolkit/devices.local.json"
    home = Path(sys.executable).resolve().parent
    for candidate in (home, *list(home.parents)[:3]):
        if (candidate / "tools/web-debug-toolkit.py").is_file() and (candidate / "work/player-control/index.html").is_file():
            return candidate / "local/web-toolkit/devices.local.json"
    return home / "用户数据/devices.local.json"


def existing_desktop(port, store):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
    try:
        connection.request("GET", "/toolkit-health")
        response = connection.getresponse()
        data = json.loads(response.read(2048))
        identity = hashlib.sha256(os.path.normcase(str(store.resolve())).encode()).hexdigest()
        return response.status == 200 and data.get("app") == "living-room-player-web" and data.get("desktop") is True and data.get("store") == identity
    except (OSError, ValueError, http.client.HTTPException):
        return False
    finally:
        connection.close()


def self_test(store):
    # Actual packaged resource, HTTP and cryptography checks. No receiver access.
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding, rsa
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    oaep = padding.OAEP(mgf=padding.MGF1(hashes.SHA1()), algorithm=hashes.SHA1(), label=None)
    assert key.decrypt(key.public_key().encrypt(b"packaged-pairing-check", oaep), oaep) == b"packaged-pairing-check"
    server = toolkit.create_server(0, store)
    server.desktop = True
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        port = server.server_port
        assert existing_desktop(port, store)
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
        conn.request("GET", "/")
        response = conn.getresponse()
        page = response.read()
        conn.close()
        assert response.status == 200 and b"window.toolkitToken=" in page and len(page) > 10000
        return {"ok": True, "page_bytes": len(page), "crypto": "RSA-OAEP roundtrip", "loopback": True}
    finally:
        server.shutdown()
        server.server_close()
        worker.join(3)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8760)
    parser.add_argument("--data-dir", type=Path)
    parser.add_argument("--no-browser", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--tray-self-test", action="store_true")
    parser.add_argument("--result", type=Path)
    args = parser.parse_args()
    store = args.data_dir / "devices.local.json" if args.data_dir else default_store()
    if args.self_test:
        result = self_test(store)
        if args.result:
            args.result.write_text(json.dumps(result), encoding="utf-8")
        return
    root = tk.Tk()
    root.withdraw()
    edition = "EXE 独立版" if getattr(sys, "frozen", False) else "源码启动版"
    root.title("客厅 WEB 工具 · " + edition)
    url = "http://127.0.0.1:" + str(args.port) + "/"
    if existing_desktop(args.port, store):
        if not args.no_browser:
            webbrowser.open(url)
        root.destroy()
        return
    try:
        import cryptography  # Fail before showing a seemingly working pairing UI.
        server = toolkit.create_server(args.port, store)
    except OSError:
        messagebox.showerror("无法启动", "本机端口已被占用。请先关闭原来的 WEB 工具，再启动此版本。", parent=root)
        root.destroy()
        return
    except Exception:
        messagebox.showerror("无法启动", "无法读取工具组件或设备档案。源码版请先安装 tools/web-requirements.txt 中的依赖；不要删除已有配对文件。", parent=root)
        root.destroy()
        return
    server.desktop = True
    worker = threading.Thread(target=server.serve_forever, daemon=True, name="loopback-web")
    worker.start()
    url = "http://127.0.0.1:" + str(server.server_port) + "/"
    root.configure(bg="#101720")
    root.geometry("460x270")
    root.minsize(460, 270)
    root.option_add("*Font", "{Microsoft YaHei UI} 10")
    tk.Label(root, text="客厅 WEB 工具", fg="#edf3fa", bg="#101720", font=("Microsoft YaHei UI", 17, "bold")).pack(anchor="w", padx=24, pady=(22, 8))
    tk.Label(root, text="● 本机服务运行中 · " + edition, fg="#80dfb5", bg="#101720").pack(anchor="w", padx=24)
    tk.Label(root, text=url, fg="#b2bfd0", bg="#101720").pack(anchor="w", padx=24, pady=(5, 12))
    hint = tk.Label(root, text="关闭窗口收进系统托盘，工具继续运行。\n双击托盘恢复；右键菜单可停止并退出。", justify="left", fg="#b2bfd0", bg="#101720")
    hint.pack(anchor="w", padx=24)
    row = tk.Frame(root, bg="#101720")
    row.pack(fill="x", padx=24, pady=18)
    tk.Button(row, text="打开控制页面", command=lambda: webbrowser.open(url), bg="#263748", fg="#edf3fa", activebackground="#38516b", activeforeground="#ffffff", relief="flat", padx=15, pady=7).pack(side="left")

    tray = None
    closing = False

    def show_window():
        root.deiconify()
        root.state("normal")
        root.lift()
        root.focus_force()

    def hide_window():
        if tray is not None and tray.registered:
            root.withdraw()
        else:
            messagebox.showwarning("托盘暂不可用", "窗口暂时无法收入托盘，工具仍在运行。可以最小化窗口，或点击“停止并退出”。", parent=root)

    def close():
        nonlocal closing
        if closing:
            return
        closing = True
        root.withdraw()
        if tray is not None:
            tray.close()
        server.shutdown()
        server.server_close()
        worker.join(3)
        root.destroy()

    tk.Button(row, text="停止并退出", command=close, bg="#663039", fg="#fff0f1", activebackground="#803c47", activeforeground="#ffffff", relief="flat", padx=15, pady=7).pack(side="right")
    if os.name == "nt":
        try:
            from web_tray import WindowsTray
            tray = WindowsTray(root, show_window, lambda: webbrowser.open(url), close)
        except Exception:
            hint.configure(text="托盘暂不可用，请最小化窗口继续使用。\n需要退出时点击“停止并退出”。")
    root.protocol("WM_DELETE_WINDOW", hide_window)
    root.update_idletasks()
    root.minsize(max(460, root.winfo_reqwidth()), max(270, root.winfo_reqheight()))
    root.geometry("")
    root.deiconify()
    if args.tray_self_test:
        def check_tray():
            import ctypes
            result = {}
            try:
                assert tray is not None and tray.registered, "Tray registration failed"
                registered, rect = tray.rectangle()
                assert registered == 0, "Tray rectangle is unavailable"
                root.tk.call(root.protocol("WM_DELETE_WINDOW"))
                root.update()
                assert root.state() == "withdrawn", "Close did not hide the window"
                assert existing_desktop(server.server_port, store), "Hiding stopped the service"
                tray._window_proc(tray.hwnd, tray.CALLBACK, 0, 0x203)
                root.update()
                assert root.state() == "normal", "Tray restore did not show the window"
                tray.shell.Shell_NotifyIconW(2, ctypes.byref(tray.data))
                tray._window_proc(tray.hwnd, tray.restart_message, 0, 0)
                root.update()
                assert tray.rectangle()[0] == 0, "Tray re-registration failed"
                result = {"ok": True, "tray_hresult": registered, "rectangle": rect, "close_hides": True, "hidden_service_running": True, "restore_window": True, "re_register": True}
            except Exception as error:
                result = {"ok": False, "error": str(error)}
            finally:
                close()
                result["tray_removed"] = tray is not None and not tray.registered
                result["service_stopped"] = not existing_desktop(server.server_port, store)
                if args.result:
                    args.result.write_text(json.dumps(result), encoding="utf-8")
        root.after(500, check_tray)
    if not args.no_browser:
        root.after(200, lambda: webbrowser.open(url))
    root.mainloop()


if __name__ == "__main__":
    main()
