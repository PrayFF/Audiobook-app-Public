# 听书（BookListen）

当前版本：`1.0.0-alpha15`。

一款无广告、无账号、无分析追踪的私人 Android 听书应用。书籍、网页正文、朗读缓存和播放进度全部保存在手机本地。

## 已实现

- 导入 UTF-8 / GB18030 TXT，自动识别常见中文章节标题。
- 导入无 DRM 的 EPUB 2/3，按 OPF Spine 顺序读取章节。
- 内置 HTTPS 浏览器支持输入书名、粘贴完整网址或裸域名，从当前页提取正文，并识别同站“下一章/下一页”。
- 系统 TextToSpeech 音色，预生成分块音频并由 Media3 后台播放。
- 播放/暂停、跨语音块前后 15 秒、上一章/下一章、0.75×–2.5×、音色选择、定时停止。
- 原文显示、当前语音块高亮、书架与精确恢复进度。
- Room 本地数据库、DataStore 设置、WorkManager 自动清理 14 天前或超出 512 MB 的语音缓存。
- 网页导入会优先提取小说元数据、结构化数据和面包屑中的书名，并过滤章节名、站点名和常见分类名。
- 网页章节会在头尾清除站点推广、分页/章节导航、连续分隔符与“阅读模式/转码阅读”提示；后续自动加载章节也使用同一规则。
- 书架支持手动重命名；章节进度条按整章内容计算。

## 项目结构

```text
app/
  src/main/        Android 应用源码与资源
  src/test/        本地单元测试
  schemas/         Room 数据库 Schema
gradle/wrapper/    Gradle Wrapper
booklisten-alpha15.apk  当前推荐安装包
booklisten-alpha11.apk  整章进度条基线版本安装包
```

## 安装包

- `booklisten-alpha15.apk`：当前版本，建议覆盖安装以保留书架和播放进度。
- `booklisten-alpha11.apk`：历史基线版本，仅用于回退对照。

## 安全与边界

- 仅加载 HTTPS；不自动登录，不保存网站账号，不绕过验证码、付费墙、DRM 或访问限制。
- 网页正文识别是启发式能力。网站改版、强反爬和复杂动态页面可能需要在浏览器中换到正文页后重试。
- EPUB 解析器专注于可朗读文本，不实现复杂排版、媒体叠加或 DRM。
- 本地文件选择器显示全部文件，但导入层只接受 TXT 或无 DRM EPUB；会结合扩展名、MIME 和文件头识别格式。

## 构建

需要 JDK 17、Android SDK 37 和 Android Studio（或 Gradle 9.4.1）。在 Android Studio 中打开项目、等待依赖同步，然后运行 `app`；命令行可执行：

```powershell
./gradlew.bat testDebugUnitTest assembleDebug
```

调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。安装到 Android 13+ 时请允许通知权限，否则后台播放仍可运行但媒体通知可能不可见。

## 不提交的本地内容

本仓库不包含 Android SDK、JDK、Gradle 缓存、构建产物、设备上的书籍和语音缓存、`local.properties`，以及任何签名密钥或密钥配置。
