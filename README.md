# PCLA — Plain Craft Launcher for Android

第三方 Android 修改版，与龙腾猫跃及 PCL 官方无关。

- [中文说明 / 中文介绍](README_CN.md)
- [English introduction](README_EN.md)

☕ 想支持这个项目？请去爱发电：https://afdian.com/a/L38324R14122

## 仓库结构

```
project/            可构建的 Android 工程（Gradle）
  app/              界面 + 下载/解压/启动的胶水层（Kotlin + 少量 C）
  core/             与 Android 无关的纯逻辑（版本 json、classpath、自检 115 项）
  download/         多线程下载（断点续传 + SHA1 校验）
  docs/             设计记录（ROADMAP、LEGACY、legacy-fork 里的早期原型）
  tools/            开发期用的脚本与实测数据（DeviceProbe、GC 日志分析、fps.csv）
.github/            GitHub Actions：跑自检 + 出 Debug APK
```

## 暂时用不到的原始资源

都放在 `garbage/`（**只搬不删**，清单见 [garbage/README.md](garbage/README.md)）：
`lwjgl/`（3.3.3+3.4.1 原件）、`armeabi-v7a/`（32 位 shell 库）、`caciocavallo/`、`caciocavallo17/`、
根目录旧档位表。要用时 `git mv` 回原位。

装进 APK 的只有 `project/app/src/main/assets/` 与 `jniLibs/` 里挑出来的子集。

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)
