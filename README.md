# 翻译（Translator）

轻量的 Windows 桌面翻译小工具：无边框小窗，输入或粘贴文本后停顿 800ms 自动翻译，结果直接显示在输出框。当前仅支持中英互译，翻译引擎为百度翻译。

## 环境要求

- Java 17
- Windows（凭证加密依赖 Windows DPAPI，常驻托盘依赖系统托盘）
- Maven

## 快速开始

本仓库只包含 `com.example.test.translator` 包的源码，Maven 工程根目录（`pom.xml` 所在目录）在本目录的 **7 级上层**，路径形如 `F:\Jet-Projects\test`。以下命令都要在 Maven 根目录执行：

```bash
# 开发运行
mvn javafx:run

# 打包可执行 fat jar
mvn package
java -jar target/test-1.0-SNAPSHOT-custom.jar
```

`pom.xml` 中 `javafx-maven-plugin`、`maven-jar-plugin`、`maven-assembly-plugin` 的 `mainClass` 均已指向本程序入口 `com.example.test.translator.TranslatorMain`。

## 使用

1. 点窗口右上角 ⚙，填入百度翻译的 **APPID** 和 **密钥**，保存。凭证经 Windows DPAPI 加密后落盘到 `%USERPROFILE%\.translator\credentials.dat`，代码中不出现密钥（决策记录见 `docs/adr/0001-windows-dpapi-for-credentials.md`）。未配置凭证时，输出框会提示「请先在设置中配置 APPID 和密钥」。
2. 选择源语言（自动检测 / 中文 / 英文，默认自动检测）与目标语言（中文 / 英文，默认英文），输入文本，停顿后自动翻译。
3. 点「复制」复制译文，点「清空」清空输入。

功能一览：

| 入口 | 作用 |
| --- | --- |
| 📌 | 切换窗口始终置顶 |
| ─ | 最小化到托盘 |
| ⇄ | 互换源语言与目标语言（仅交换语言选择，不交换输入输出文本） |
| 输入框右下「清空」 | 清空输入内容 |
| 输出框右下「复制」 | 复制全部译文 |
| 托盘图标 | 单击 / 双击恢复窗口；右键菜单「显示窗口 / 退出」 |
| 顶栏 | 整条顶栏（按钮上除外）可拖动移动窗口 |

翻译失败（网络异常、凭证错误、引擎返回错误码等）时，错误信息内联显示在输出框中，不弹窗。

## 目录结构

```
translator/
├── TranslatorMain.java      # 程序入口：无边框主窗口 + 系统托盘 + 托盘菜单
├── Launcher.java            # fat jar 启动入口（见下方说明）
├── model/
│   └── Lang.java            # 语言枚举：接口代码 + 界面显示文本
├── service/
│   ├── Translator.java      # 翻译引擎接口（可插拔）
│   ├── BaiduTranslator.java # 百度翻译实现：MD5 签名 + HTTP 请求 + 响应解析
│   ├── CredentialStore.java # 凭证加密存取（Windows DPAPI）
│   └── TranslationException.java
└── ui/
    ├── MainView.java        # 主界面：顶栏、语言选择、输入/输出框、防抖翻译调度
    ├── SettingsView.java    # 设置窗口：APPID + 密钥
    └── Debouncer.java       # 防抖：停顿 800ms 后触发
```

界面样式在 Maven 根目录的 `src/main/resources/css/translator.css`，由 `MainView` 通过 `/css/translator.css` 加载；`src/assembly/custom.xml` 负责把它打进 fat jar。

## 实现要点

- **可插拔引擎**：`Translator` 是唯一抽象，`BaiduTranslator` 是其唯一实现。新增引擎只需实现该接口并在 `MainView#reloadCredentials` 中改用新实现。
- **防抖 + 过期请求丢弃**：每次输入变化都重置 800ms 计时；每次发起请求自增序号，只有序号仍是最新的结果才会写入输出框，避免快速连续输入时旧结果覆盖新结果。
- **线程模型**：翻译请求跑在专用的单线程池（`translator-worker`，守护线程）上，不占用 `ForkJoinPool` 公共池；结果写 UI 前统一切回 `Platform.runLater`。
- **百度接口**：`POST https://fanyi-api.baidu.com/api/trans/vip/translate`，签名 `MD5(appid + q + salt + 密钥)`，连接与请求超时均为 5 秒；响应中的 `dst` 与错误码用预编译正则提取并解码 `\uXXXX`，未引入 JSON 库。
- **托盘菜单自绘**：托盘右键菜单用 JavaFX `Popup` 自绘而非 `PopupMenu`，避免 AWT 菜单在中文环境下乱码。菜单 owner 是一个屏幕外的透明 1×1 工具窗口——主窗口最小化到托盘后 `primaryStage` 处于 hidden 状态，而 `Popup.show` 要求 owner 的 root window 正在显示，否则会静默失败。
- **图标自绘**：程序图标由 `Graphics2D` 运行时绘制（蓝底圆角 + 白色「译」字），不依赖外部图片资源。
- **`Launcher` 与 `TranslatorMain` 的分工**：fat jar 场景不能直接把 `TranslatorMain`（继承 `javafx.application.Application`）设为启动主类——JDK launcher 会去 boot module layer 找 `javafx.graphics`，而 fat jar 里的 JavaFX 在 classpath 上，会报「JavaFX runtime components are missing」。`Launcher` 是一个不继承 `Application` 的普通类，通过 `Application.launch(...)` 间接启动来绕过这个检查，同时把 `javafx` logger 提到 SEVERE，屏蔽从 classpath 加载时的误导性 WARNING。因此 `java -jar` 请走 `Launcher`，`mvn javafx:run` 走 `TranslatorMain`。
- **内存约束**：为小工具常驻场景收紧 JVM 参数（`-Xmx128m`、`MaxMetaspaceSize=128m`、`ReservedCodeCacheSize=64m`、`MaxDirectMemorySize=32m`、`UseSerialGC`），见 `pom.xml`。

## 扩展：接入新的翻译引擎

1. 在 `service` 下新建类实现 `Translator#translate(String text, String from, String to)`。
2. 在 `MainView#reloadCredentials` 中按新的凭证形状构造该实现。
3. 若凭证结构不同，同步调整 `CredentialStore` 的读写格式。

注意 `Translator` 接口以 `Exception` 上抛失败，实现方抛出异常时 UI 会显示为「翻译失败：<message>」，请尽量给出用户可读的信息。

## 已知限制

- 仅 Windows（DPAPI、系统托盘），不做跨平台。
- 仅中英互译，仅百度翻译一个引擎；`Lang` 增加枚举值后需同步确认引擎支持的代码。
- 窗口尺寸固定 480×260，顶栏可拖动但不能边缘缩放。
- 不做单实例限制。
- 项目中没有测试代码，改动需手工验证。

## 构建配置待修正

`pom.xml` 中 `jpackage-maven-plugin` 的 `mainJar` 配置为 `${project.build.finalName}-jar-with-dependencies.jar`，但 `maven-assembly-plugin`（descriptor 的 `<id>custom</id>`）实际产出的是 `${project.build.finalName}-custom.jar`，两者不匹配，`mvn jpackage:jpackage` 会找不到主 jar。要么把 assembly 的 `<id>` 改成 `jar-with-dependencies`，要么把 `mainJar` 改成 `-custom.jar`。
