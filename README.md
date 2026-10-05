<div align="center">

<img width="200" src="https://raw.githubusercontent.com/CCBlueX/LiquidCloud/master/LiquidBounce/liquidbounceLogo.svg">

# PojavBounceNew

### 让 LiquidBounce NextGen 在 Android 上完美运行

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.3-green.svg)](https://minecraft.net)
[![Fabric](https://img.shields.io/badge/Mod%20Loader-Fabric-orange.svg)](https://fabricmc.net)
[![Platform](https://img.shields.io/badge/Platform-Android-red.svg)](https://pojavlauncher.net)
[![Bilibili](https://img.shields.io/badge/Bilibili-@Seagdo-FF69B4.svg)](https://b23.tv/tTuEWKE)

一个免费、开源的基于 Mixin 注入的 Minecraft Java 版实用客户端，专为 **PojavLauncher / ZalithLauncher** 等 Android 启动器适配。

</div>

---

## 📖 项目简介

**PojavBounceNew** 是 [LiquidBounce NextGen](https://github.com/CCBlueX/LiquidBounce) 在 Android 平台的适配版本。项目基于 **Rubbishy-Liquidbounce-NextGen For Android** 以及 **LiquidBounce NextGen 0.40.1** 开发，旨在让最新版 LiquidBounce 在安卓设备上流畅运行。

### 🌟 核心特性

| 特性 | 说明 |
|------|------|
| 🎮 **原生 ClickGUI** | 纯 Kotlin 原生渲染的 ClickGUI，不依赖 JCEF 浏览器 |
| 📱 **Android 全适配** | 禁用 JCEF/Discord RPC/DJL 等 PC 独有功能，避免崩溃 |
| 🔤 **内置字体材质包** | 自带中文字体与 Emoji 纹理，无需额外安装 |
| ⚡ **模块系统** | ArrayList、TargetInfo、Notifications、StatusBars 等实用模块 |
| 🖥️ **原生 HUD** | 非 Web 的纯原生 HUD 渲染，性能更优 |
| 🔧 **完整命令系统** | 支持通过聊天命令配置所有模块与设置 |

### 👥 开发团队

| 贡献者 | 贡献占比 | 角色 |
|--------|---------|------|
| **Cleverpeople** | 50% | Rubbishy-LB 原作者 提供了一些视觉模块|
| **Seagdo** (Bilibili) | 45% | Android 适配 · 功能移植与修复增强 · 项目维护 |
| **Geda6** | 5% | 功能测试 · Bug 修复 |

> 开发工具：DeepSeekV4Pro,GLM-5.2等 AI 辅助编程

---

## 🚀 安装教程

### 第一步：准备工作

1. 在手机上安装以下启动器之一：
   - [PojavLauncher](https://pojavlauncherteam.github.io/) 
   - [ZalithLauncher](https://github.com/ZalithMC/ZalithLauncher)(推荐)
   - FoldCraft Launcher

2. 确保启动器中已安装：
   - **Minecraft Java Edition** 26.3（1.21.x）
   - **Fabric Loader** 0.19.5+
   - **Fabric API** 0.160.5+
   - **Fabric Language Kotlin** （必须安装！）

### 第二步：下载客户端

1. 前本项目的 [Releases](../../releases) 页面
2. 下载最新的 `.jar` 文件（如 `PojavbounceNew-0.40.1.jar`）

### 第三步：安装到手机

1. 将下载的 `.jar` 文件放入手机的 Minecraft mods 目录：
   ```
   内部存储/FCL/.minecraft/mods/
   ```
   或（ZalithLauncher）：
   ```
   内部存储/ZalithLauncher/.minecraft/mods/
   ```

2. 如果 `mods` 文件夹不存在，手动创建即可

### 第四步：启动游戏

1. 打开 PojavLauncher / ZalithLauncher
2. 选择对应的 Minecraft 版本（26.3）
3. 使用MobileGlues最新的支持26.3的测试版本以启动26.3JavaMC
4. 启动游戏

---

## 📱 使用教程

### 打开 ClickGUI（功能面板）

| 操作 | 按键 |
|------|------|
| 打开/关闭 ClickGUI | `RightShift`（右 Shift） |
| 关闭 ClickGUI | `ESC` |

> 💡 **触屏提示**：在 PojavLauncher 的控制设置中，将一个虚拟按钮绑定到 `RightShift` 键，即可一键打开 ClickGUI

### ClickGUI 操作

| 操作 | 方式 |
|------|------|
| 切换模块开关 | 点击模块名称 |
| 展开模块设置 | 点击模块右侧箭头 |
| 拖动面板 | 拖拽面板标题栏 |
| 搜索模块 | 在搜索框输入模块名 |

### 常用命令

在游戏聊天框中输入以下命令：

| 品令 | 功能 | 示例 |
|------|------|------|
| `.help` | 查看所有命令 | `.help` |
| `.bind <模块> <按键>` | 绑定快捷键 | `.bind KillAura R` |
| `.toggle <模块>` | 切换模块开关 | `.toggle KillAura` |
| `.localconfig load <配置名>` | 加载本地配置 | `.localconfig load hypixel(导入了的话)` |
| `.config load <名称>` | 加载水影官方配置 | `.config load default` |

### 内置模块一览

<details>
<summary>📦 点击展开完整模块列表</summary>

| 分类 | 模块 |
|------|------|
| ⚔️ Combat | KillAura, AutoBow, Criticals, AutoArmor, HitBox... |
| 🏃 Movement | Fly, Speed, Sprint, NoFall, AutoJump, Scaffold... |
| 🎨 Render | ESP, Tracers, Nametags, FullBright, XRay, FreeCam... |
| 🌍 World | Nuker, AutoMine, FastPlace, ScaffoldWalk... |
| 🧑 Player | AutoEat, FastEat, NoSlowdown, Reach... |
| 🔧 Misc | AntiAFK, TeamChecker, CustomChat... |
| 🎉 Fun | Derp, Twerk, HandDerp... |

</details>

---

## 🛠️ 构建指南

### 环境要求

- **JDK** 25+
- **Gradle** 9.6+（项目自带 wrapper）
- 网络连接（需下载依赖）

### 构建步骤

```bash
# 1. 克隆仓库
git clone --recurse-submodules https://github.com/your-repo/PojavBounceNew.git
cd PojavBounceNew

# 2. 构建（跳过测试和代码检查）
./gradlew build -x test -x detekt

# 3. 构建产物在
# build/libs/liquidbounce-0.40.1.jar
```

### Android 专用构建

```bash
# 使用 Android 专用构建配置
cp gradle-android.properties gradle.properties
./gradlew build -x test -x detekt
```

### 开发调试

```bash
# 反编译 Minecraft 源码（便于开发）
./gradlew genSources

# 在 IDE 中打开项目（IntelliJ IDEA 推荐）
# 直接打开 build.gradle.kts 即可

# 运行客户端（桌面调试）
./gradlew runClient
```

---

## 🧩 技术架构

### Mixin 注入

PojavBounceNew 使用 [Mixin](https://docs.spongepowered.org/5.1.0/en/plugin/internals/mixins.html) 在运行时修改 Minecraft 的字节码，将客户端代码注入到原版游戏中。这种方式**不包含任何 Mojang 的版权代码**。

### Android 适配原理

本项目通过以下方式适配 Android 平台：

| 适配项 | 方案 |
|-------|------|
| JCEF 浏览器后端 | 禁用并替换为原生 Kotlin ClickGUI |
| DJL 深度学习 | 禁用（无 ARM 原生库） |
| Discord RPC | 禁用（无 Android 原生库） |
| AWT 字体渲染 | 捕获 HeadlessException，降级为默认值 |
| MCEF HTTP 拦截器 | 改为反射加载，不存在时降级 |
| Theme 前端构建 | 禁用 npm/Node.js 构建任务 |

---

## 🤝 贡献

欢迎提交 Pull Request 和 Issue！如果你发现了 Bug 或有功能建议，请通过 [Issues](../../issues) 页面告诉我们。

### 贡献指南

1. Fork 本仓库
2. 创建功能分支：`git checkout -b feature/amazing-feature`
3. 提交更改：`git commit -m 'Add amazing feature'`
4. 推送分支：`git push origin feature/amazing-feature`
5. 提交 Pull Request

---

## 📜 开源许可

本项目基于 [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.en.html) 开源。

### 你可以

- ✅ 自由使用
- ✅ 自由分享
- ✅ 自由修改

### 你必须

- 🔒 **开源你的修改**：如果你使用了本项目的代码（即使是部分），你的作品也必须以 GPL 协议开源，不得闭源或混淆代码

---

## 🙏 鸣谢

<div align="center">

| 项目 | 贡献 |
|------|------|
| [LiquidBounce NextGen](https://github.com/CCBlueX/LiquidBounce) | 原始项目 · CCBlueX 团队 |
| [Rubbishy-Liquidbounce](https://github.com/Cleverpeople/Rubbishy-Liquidbounce) | Android 适配基础 · ArrayList/TargetInfo/Notifications 模块 |
| [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) | Android Java 版 MC 启动器 |
| [FabricMC](https://fabricmc.net/) | 模块加载框架 |

**特别感谢**：CCBlueX、Cleverpeople、Geda6以及所有 LiquidBounce 社区成员

</div>

---

## 📊 项目统计

![Repobeats](https://repobeats.axiom.co/api/embed/ad3a9161793c4dfe50934cd4442d25dc3ca93128.svg)

---

## 📮 联系方式

| 平台 | 链接 |
|------|------|
| 📺 Bilibili 频道| [https://b23.tv/tTuEWKE](https://b23.tv/tTuEWKE) |
| 🐛 Bug 反馈 | [Issues](../../issues) |
| 💬 讨论 | [Discussions](../../discussions) |

---

<div align="center">

**Made with ❤️ for Android Minecraft Community**

*Star ⭐ this project if you like it!*

</div>
