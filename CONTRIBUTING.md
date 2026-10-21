# 参与开发

本项目是面向可信家庭局域网的实验性播放器控制工具，优先保持轻量、设备自主配置和本地数据处理。提交问题前请阅读 README 与兼容性说明。

## 开发与检查

使用 Windows、PowerShell 7、JDK 17、Python 3.13、Node.js 22，以及 README 中列出的 Android SDK。设置 `JAVA_HOME` 后运行：

```powershell
python -m pip install -r tools/web-requirements.txt
python tools/check.py
./tools/format-java.ps1 -Check
./tools/check-secrets.ps1
python tools/check-public-metadata.py
./work/android-player/build.ps1
./work/android-diagnostics/build.ps1
```

统一入口在缺少 JDK、Node.js 或发生跳过测试时返回失败。测试不连接实际手机或盒子，不证明设备兼容性或音频品质。GitHub Actions 使用临时签名，仅验证构建，不产生正式发行版。

Java 源码采用 google-java-format 1.22.0 的格式（保留长字符串，不自动重排字符串内容）。运行 `./tools/format-java.ps1` 可自动整理；首次从上游下载工具并验证 SHA256，保存在忽略的本地目录。保持修改范围可审阅；行为修改应说明触发条件、预期结果、验证方式及设备限制。不要为了格式整理同时改变权限或硬件控制逻辑。

## 提交内容

只提交应用源码、必要工具、文档和测试。禁止提交签名密钥、连接码、设备档案、系统日志、截图中的私人信息、音乐、固件、本地绝对路径及开发对话。测试使用合成凭据和示例地址。

提交作者和提交者统一使用 GitHub 隐私邮箱（`…@users.noreply.github.com`；GitHub 自动提交的 `noreply@github.com` 也允许）。在 GitHub 的邮箱设置中启用隐私邮箱，再核对网页合并生成的作者身份；本机 Git 配置不会改变 GitHub 网页合并身份。自动检查扫描已获取的全部引用，发现其他邮箱时只报告提交编号，不把邮箱再次写进构建日志。

新增控制能力应明确由受控端配置还是控制端执行，不增加不必要授权。ROOT、充电、HDMI、CEC、实体按键和待机相关改动必须注明实际设备与固件，并与模拟测试结果区分。

## 反馈与贡献

功能问题请通过仓库 Issues 提交，包含应用版本、设备型号、Android 版本、复现步骤、预期和实际行为。先脱敏，勿上传完整系统日志。安全问题按 SECURITY.md 私下报告。

提交贡献意味着你有权按仓库 MIT 许可证提供这些修改；第三方代码与素材须保留原许可证和来源。

敏感信息检查使用固定版本和 SHA256 的 Gitleaks，扫描本地 Git 历史；它不扫描尚未提交的工作文件，也不能识别全部私人信息。提交前仍需审查暂存内容，公开前还应人工检查日志、地址和账号等信息。
