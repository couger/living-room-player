# 外部依赖与来源

播放器 APK 使用 Android 平台 API，当前没有随 APK 打包第三方 Java 库。音乐 APP、ROOT 管理器、固件和媒体文件由用户自行提供，不包含在本项目许可证或发行内容中。

PC 工具通过 pip 单独安装以下依赖，保持各自许可证：

| 项目 | 用途 | 上游 |
| --- | --- | --- |
| cryptography | 配对 RSA 密钥及加密处理 | https://github.com/pyca/cryptography |
| cffi、pycparser | cryptography 的安装依赖，由 pip 解析 | https://github.com/python-cffi/cffi 、https://github.com/eliben/pycparser |

直接依赖版本固定在 `tools/web-requirements.txt`，Dependabot 提供更新建议；这不是全部传递依赖的哈希锁定，也不保证跨平台二进制逐字节相同。依赖更新需重新运行协议与网络测试。

构建使用用户或 CI 安装的 OpenJDK、Android SDK；格式整理工具来自 [google-java-format](https://github.com/google/google-java-format)，采用 Apache-2.0 许可证。工具 JAR 下载后验证固定 SHA256，保存在忽略的本地目录，不提交或打包进 APK。

引入新库、图标或素材时应补充来源和许可证，并确认是否随发行物分发。
