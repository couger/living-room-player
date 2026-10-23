"""Native Windows notification icon hosted on Tk's existing message loop."""
import ctypes
from ctypes import wintypes as w
import struct


def icon_dib(size):
    pixels = bytearray()
    for y in reversed(range(size)):
        for x in range(size):
            channels = [0, 0, 0, 0]
            for sy in range(4):
                for sx in range(4):
                    px = (x + (sx + .5) / 4) * 32 / size
                    py = (y + (sy + .5) / 4) * 32 / size
                    radius = (px - 16) ** 2 + (py - 16) ** 2
                    if radius > 15 ** 2:
                        continue
                    color = (38, 55, 72) if radius > 13.7 ** 2 else (16, 23, 32)
                    if 11.5 <= px <= 23 and abs(py - 16) <= .64 * (23 - px):
                        color = (128, 223, 181)
                    for i, value in enumerate((*reversed(color), 255)):
                        channels[i] += value
            pixels.extend(round(value / 16) for value in channels)
    mask = bytes(((size + 31) // 32) * 4 * size)
    return struct.pack('<IiiHHIIiiII', 40, size, size * 2, 1, 32, 0, len(pixels), 0, 0, 0, 0) + pixels + mask


def icon_file():
    images = [(size, icon_dib(size)) for size in (16, 32, 48, 64)]
    offset = 6 + 16 * len(images)
    entries = bytearray()
    for size, data in images:
        entries.extend(struct.pack('<BBBBHHII', size, size, 0, 0, 1, 32, len(data), offset))
        offset += len(data)
    return struct.pack('<HHH', 0, 1, len(images)) + entries + b''.join(data for _, data in images)


class GUID(ctypes.Structure):
    _fields_ = [('a', w.DWORD), ('b', w.WORD), ('c', w.WORD), ('d', w.BYTE * 8)]


class NotifyData(ctypes.Structure):
    _fields_ = [('cbSize', w.DWORD), ('hWnd', w.HWND), ('uID', w.UINT), ('uFlags', w.UINT),
                ('uCallbackMessage', w.UINT), ('hIcon', w.HICON), ('szTip', w.WCHAR * 128),
                ('dwState', w.DWORD), ('dwStateMask', w.DWORD), ('szInfo', w.WCHAR * 256),
                ('uVersion', w.UINT), ('szInfoTitle', w.WCHAR * 64), ('dwInfoFlags', w.DWORD),
                ('guidItem', GUID), ('hBalloonIcon', w.HICON)]


class IconId(ctypes.Structure):
    _fields_ = [('cbSize', w.DWORD), ('hWnd', w.HWND), ('uID', w.UINT), ('guidItem', GUID)]


class WindowsTray:
    CALLBACK = 0x8001

    def __init__(self, root, show, open_page, quit_app):
        self.root = root
        self.actions = {1: open_page, 2: show, 4: quit_app}
        self.user = ctypes.WinDLL('user32', use_last_error=True)
        self.shell = ctypes.WinDLL('shell32', use_last_error=True)
        self.kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        self.hwnd = None
        self.icon = None
        self.registered = False
        self._setup_api()
        self.restart_message = self.user.RegisterWindowMessageW('TaskbarCreated')
        self.class_name = 'LivingRoomWebTray.' + str(id(self))
        self.instance = self.kernel.GetModuleHandleW(None)
        self.callback = self.WNDPROC(self._window_proc)
        klass = self.WNDCLASS()
        klass.lpfnWndProc = self.callback
        klass.hInstance = self.instance
        klass.lpszClassName = self.class_name
        if not self.user.RegisterClassW(ctypes.byref(klass)):
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            self.hwnd = self.user.CreateWindowExW(0, self.class_name, 'LivingRoomWebTray', 0, 0, 0, 0, 0, None, None, self.instance, None)
            if not self.hwnd:
                raise ctypes.WinError(ctypes.get_last_error())
            dib = icon_dib(32)
            buffer = ctypes.create_string_buffer(dib)
            self.icon = self.user.CreateIconFromResourceEx(buffer, len(dib), True, 0x00030000, 32, 32, 0)
            if not self.icon:
                raise ctypes.WinError(ctypes.get_last_error())
            self.data = NotifyData()
            self.data.cbSize = ctypes.sizeof(NotifyData)
            self.data.hWnd, self.data.uID = self.hwnd, 1
            self.data.uFlags = 1 | 2 | 4 | 128  # message, icon, standard tooltip, version-4 tooltip
            self.data.uCallbackMessage, self.data.hIcon = self.CALLBACK, self.icon
            self.data.szTip = '客厅 WEB 工具 · 本机运行中\n双击显示窗口；右键打开菜单'
            if not self.add():
                raise OSError('Windows notification area did not accept the icon')
            root.update_idletasks()
            owner = self.user.GetAncestor(root.winfo_id(), 2)
            self.user.SendMessageW(owner, 0x80, 0, self.icon)
            self.user.SendMessageW(owner, 0x80, 1, self.icon)
        except Exception:
            self.close()
            raise

    def _setup_api(self):
        self.WNDPROC = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, w.HWND, w.UINT, w.WPARAM, w.LPARAM)
        class WNDCLASS(ctypes.Structure):
            _fields_ = [('style', w.UINT), ('lpfnWndProc', self.WNDPROC), ('cbClsExtra', ctypes.c_int),
                        ('cbWndExtra', ctypes.c_int), ('hInstance', w.HINSTANCE), ('hIcon', w.HICON),
                        ('hCursor', w.HANDLE), ('hbrBackground', w.HBRUSH), ('lpszMenuName', w.LPCWSTR), ('lpszClassName', w.LPCWSTR)]
        self.WNDCLASS = WNDCLASS
        signatures = {
            'RegisterClassW': ([ctypes.POINTER(WNDCLASS)], w.WORD),
            'CreateWindowExW': ([w.DWORD, w.LPCWSTR, w.LPCWSTR, w.DWORD, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, w.HWND, w.HMENU, w.HINSTANCE, w.LPVOID], w.HWND),
            'DefWindowProcW': ([w.HWND, w.UINT, w.WPARAM, w.LPARAM], ctypes.c_ssize_t),
            'DestroyWindow': ([w.HWND], w.BOOL), 'DestroyIcon': ([w.HICON], w.BOOL),
            'UnregisterClassW': ([w.LPCWSTR, w.HINSTANCE], w.BOOL),
            'RegisterWindowMessageW': ([w.LPCWSTR], w.UINT),
            'CreateIconFromResourceEx': ([w.LPVOID, w.DWORD, w.BOOL, w.DWORD, ctypes.c_int, ctypes.c_int, w.UINT], w.HICON),
            'CreatePopupMenu': ([], w.HMENU), 'DestroyMenu': ([w.HMENU], w.BOOL),
            'AppendMenuW': ([w.HMENU, w.UINT, ctypes.c_size_t, w.LPCWSTR], w.BOOL),
            'GetCursorPos': ([ctypes.POINTER(w.POINT)], w.BOOL), 'SetForegroundWindow': ([w.HWND], w.BOOL),
            'TrackPopupMenu': ([w.HMENU, w.UINT, ctypes.c_int, ctypes.c_int, ctypes.c_int, w.HWND, w.LPVOID], w.UINT),
            'PostMessageW': ([w.HWND, w.UINT, w.WPARAM, w.LPARAM], w.BOOL),
            'SendMessageW': ([w.HWND, w.UINT, w.WPARAM, w.LPARAM], ctypes.c_ssize_t),
            'GetAncestor': ([w.HWND, w.UINT], w.HWND),
        }
        for name, (args, result) in signatures.items():
            function = getattr(self.user, name)
            function.argtypes, function.restype = args, result
        self.kernel.GetModuleHandleW.argtypes = [w.LPCWSTR]
        self.kernel.GetModuleHandleW.restype = w.HMODULE
        self.shell.Shell_NotifyIconW.argtypes = [w.DWORD, ctypes.POINTER(NotifyData)]
        self.shell.Shell_NotifyIconW.restype = w.BOOL
        self.shell.Shell_NotifyIconGetRect.argtypes = [ctypes.POINTER(IconId), ctypes.POINTER(w.RECT)]
        self.shell.Shell_NotifyIconGetRect.restype = w.LONG

    def add(self):
        self.registered = bool(self.shell.Shell_NotifyIconW(0, ctypes.byref(self.data)))
        if self.registered:
            self.data.uVersion = 4
            self.shell.Shell_NotifyIconW(4, ctypes.byref(self.data))
        return self.registered

    def rectangle(self):
        identity = IconId(ctypes.sizeof(IconId), self.hwnd, 1, GUID())
        rect = w.RECT()
        result = self.shell.Shell_NotifyIconGetRect(ctypes.byref(identity), ctypes.byref(rect))
        return result, (rect.left, rect.top, rect.right, rect.bottom)

    def _window_proc(self, hwnd, message, wp, lp):
        if message == self.CALLBACK:
            event = lp & 0xffff
            if event in (0x203, 0x401):  # double-click / keyboard activation
                self.root.after(0, self.actions[2])
            elif event == 0x7b:  # version-4 context menu
                self.menu()
            return 0
        if message == self.restart_message and hasattr(self, 'data'):
            if not self.add():
                self.root.after(0, self.actions[2])
            return 0
        return self.user.DefWindowProcW(hwnd, message, wp, lp)

    def menu(self):
        menu = self.user.CreatePopupMenu()
        if not menu:
            return
        try:
            for identifier, text in ((1, '打开控制页面'), (2, '显示管理窗口'), (0, ''), (4, '停止并退出')):
                self.user.AppendMenuW(menu, 0x800 if identifier == 0 else 0, identifier, text)
            point = w.POINT()
            self.user.GetCursorPos(ctypes.byref(point))
            self.user.SetForegroundWindow(self.hwnd)
            selected = self.user.TrackPopupMenu(menu, 0x182, point.x, point.y, 0, self.hwnd, None)
            self.user.PostMessageW(self.hwnd, 0, 0, 0)
            if selected in self.actions:
                self.root.after(0, self.actions[selected])
        finally:
            self.user.DestroyMenu(menu)

    def close(self):
        if self.registered:
            self.shell.Shell_NotifyIconW(2, ctypes.byref(self.data))
            self.registered = False
        if self.hwnd:
            self.user.DestroyWindow(self.hwnd)
            self.hwnd = None
        if self.icon:
            self.user.DestroyIcon(self.icon)
            self.icon = None
        self.user.UnregisterClassW(self.class_name, self.instance)
