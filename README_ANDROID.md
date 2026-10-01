# LiquidBounce 26.3 for Android (PojavLauncher / ZalithLauncher)

## 适配说明

基于 LiquidBounce nextgen 0.40.1 (MC 26.3) 适配 Android 平台。

### 自动禁用的功能

| 功能 | 状态 | 原因 |
|------|------|------|
| JCEF / MCEF 浏览器 | ❌ 禁用 | Android 无 Chromium Embedded Framework |
| Discord RPC | ❌ 禁用 | 无 Android 原生库 |
| 深度学习 (DJL) | ❌ 禁用 | PyTorch 原生库无 ARM 版本 |
| 浏览器版 ClickGUI | ❌ 替换 | 使用原生 Kotlin ClickGUI 替代 |
| 浏览器版 HUD | ❌ 禁用 | 浏览器不可用 |
| Theme 前端构建 | ❌ 禁用 | Node.js 项目无法在 Android 编译 |
| 标准功能模块 | ✅ 正常 | 所有作弊模块正常工作 |
| 命令系统 | ✅ 正常 | 可用 `.help` 查看命令 |

### 构建

```bash
# 方法1: 使用标准构建（运行时自动检测 Android）
./gradlew build

# 方法2: 使用 Android 构建配置
cp gradle-android.properties gradle.properties
./gradlew build
```

### 安装

1. 将构建出的 JAR 放入 `.minecraft/mods/` 目录
2. 确保安装了 `fabric-language-kotlin`
3. 启动 Minecraft

### 使用

- **打开 ClickGUI**: 按 `RightShift`（或在 PojavLauncher 中绑定对应虚拟按键）
- **ClickGUI 操作**: 点击模块名切换开关
- **命令**: 在聊天框输入 `.help` 查看可用命令
- **模块绑定**: 使用 `.bind <模块名> <按键>` 命令绑定快捷键

### 已知限制

- ClickGUI 为简化原生版本（无搜索栏、无拖拽排序）
- HUD 浏览器组件不可用
- 自定义主题字体不加载
- 部分依赖 AWT 的字体功能降级为默认值
