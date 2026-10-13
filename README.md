# 客厅播放器 / Living Room Player

将 Android 手机、平板或兼容盒子作为家庭局域网音乐播放器。控制端和受控端使用同一个轻量原生 APK，另提供在 PC 本机运行的 WEB 工具。当前版本 **0.9.3**。

## 功能

- 多设备配对、六位码身份核对、TLS 证书固定、独立授权撤销。
- 远程屏幕、点击、长按、滑动、鼠标滚轮及横竖屏适配。
- 播放／暂停、上一首／下一首；由受控端选择音乐 APP，不调用音乐账号私有 API。
- 可选普通音乐按钮或关闭 HDMI 后播放／暂停；桌面小组件和 Android 下拉快捷按钮。
- 手机电池与供电状态、迟滞充电保护；兼容盒子的 HDMI 显示控制与实体遥控器绑定。
- 独立系统故障记录 APK，与播放器互不管理。

控制端无需 ROOT，受控端后台助手和独立故障记录器需要 ROOT。盒子显示与电源能力依赖具体固件；已适配 HK1 RBOX K8 / RK3528，不能据此保证其他型号兼容。暂停充电不等于硬件旁路，USB 独占音量、DSD、bit-perfect 与深度休眠唤醒不作保证。

## 使用

1. 在播放器设备打开受控模式并启动服务，允许该 APP 的 ROOT 权限。
2. 在受控端“安全与配对”临时允许新配对；控制端或 PC 工具搜索，核对双方六位数字后在受控端批准。
3. 在受控端本机选择音乐 APP。首次使用时需先在音乐 APP 中准备可播放的曲目。
4. 日常可以关闭系统 USB／无线调试；它们不承担应用控制连接。

PC 工具需要 Python 和 `cryptography`：

```powershell
python -m pip install -r tools/web-requirements.txt
python tools/web-debug-toolkit.py --port 8760
```

浏览器打开 `http://127.0.0.1:8760/`，也可运行根目录的 `启动WEB调试工具.cmd`。工具只监听本机；与受控端之间使用固定证书的 TLS。

## 构建与测试

需要 JDK 17、Android SDK platform `android-34`、build-tools `36.0.0`，设置 `JAVA_HOME` 和 `ANDROID_SDK_ROOT` 后运行：

```powershell
./work/android-player/build.ps1
./work/android-diagnostics/build.ps1
python -m unittest discover -s tools -p 'test_*.py'
node tools/test_web_controls.cjs
```

产物写入 `outputs/`。签名密钥及密码保存在本地，不提交版本库。覆盖安装必须沿用原签名；升级受控端后可在本机点“启动／恢复服务”更新后台助手。

## 源码目录

| 目录 | 内容 |
| --- | --- |
| `work/android-player/` | 控制端、受控端和后台助手 |
| `work/android-diagnostics/` | 独立系统故障记录应用 |
| `work/player-control/index.html` | PC WEB 工具界面 |
| `tools/` | PC 工具、诊断导出与应用回归测试 |
| `docs/` | 使用、权限和兼容性说明 |

## 使用说明

- [音乐按钮、HDMI 与 CEC](docs/music-button-modes.md)
- [快捷入口](docs/controller-shortcuts.md)、[地址变化](docs/address-management.md)
- [实体遥控器](docs/key-inspection.md)、[无屏播放](docs/screenless-music.md)
- [本机配置职责](docs/power-ownership.md)、[配对与授权](docs/security-controls.md)
- [家庭网络安全](docs/home-network-security.md)、[WEB 工具](docs/web-debug-toolkit.md)
- [盒子兼容性](docs/android-box.md)、[独立故障记录](docs/system-diagnostics.md)

凭据、设备档案、日志、截图、固件、媒体文件、构建产物和本地开发记录不属于公开源码。不要将控制服务、设备发现或 ADB 端口映射到公网。屏幕控制和 ROOT 助手具有较高权限，仅用于可信设备和网络。
