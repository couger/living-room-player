# 独立系统故障记录

独立 APK 的包名为 cn.couger.systemdiagnostics，与播放器互不管理。诊断 APP 没有网络、录音、屏幕采集或悬浮窗权限；使用本机 ROOT 记录，再由 PC 手动导出。

首次打开“开始／恢复记录”，允许该 APP 自己的 ROOT 权限。开机接收器启动短时服务，完成启动后退出；记录由 ROOT shell 和系统 logcat 进程承担。长期运行需确认 ROOT 授权不会自动过期。

## 数据管理

记录器位于 /data/adb/system-diagnostics/recorder.sh，数据位于该目录的 logs 子目录。保留最近三次启动记录，并有约 75 MiB 的预计容量上限；不自动上传。停止记录保留已有数据，卸载前应先停止，ROOT 数据目录不会随 APK 卸载自动删除。

旧版记录可用 tools/migrate-system-diagnostics.sh 迁移。工具保留原始数据，不覆盖已启动的新记录器；迁移与播放器使用互相独立。

## 导出

```powershell
python tools/export-android-diagnostics.py --adb "$env:ANDROID_SDK_ROOT/platform-tools/adb.exe" --device DEVICE_ADDRESS:PORT --output outputs/diagnostic-exports
```

导出 tar.gz 与 SHA256 清单，不归档播放器配对凭据；系统日志自身仍可能含私人信息，分享前需脱敏。运行中导出是尽力采集，不是严格同一时刻快照。

记录器在 Android 启动完成后启动，不能保证捕获更早的启动动画故障、内核崩溃或突然断电。后续正常启动时可保存固件已有的 Recovery 等记录，覆盖范围取决于固件持久化能力。
