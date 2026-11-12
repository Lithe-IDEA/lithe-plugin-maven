# Maven for Lithe

为 [Lithe](https://github.com/1lck/Lithe-IDEA) 提供 Maven 项目支持：识别 `pom.xml`，在构建窗口里列出
模块、生命周期和插件目标，运行任务和测试，查看依赖树。
这是一个**社区插件**，用插件契约 v0 的声明式构建系统接口（`buildSystems`）实现。插件没有后端进程，
只有一份 `plugin.json`、新建项目模板和一个 Maven 扩展 jar。

> English: Maven support for Lithe through the declarative `buildSystems` contract — project tree, lifecycle and
> plugin goals, profiles, test runs with JUnit reports, compiler diagnostics and the dependency tree. Lithe runs your
> own Maven with a small bundled core extension that only reads the reactor; the plugin has no backend process.

> **预发布**：按插件契约 v0（`schemaVersion: 0`、`pluginAPIVersion: 0`）编写，只能装进新版 Lithe 桌面端
> （`desktop-v*` 预发布），旧版 macOS Lithe 无法安装。

## 功能

| 功能 | 说明 |
| --- | --- |
| 识别项目 | 工作区最外层的 `pom.xml` 是构建根，`<modules>` 组成模块树 |
| 生命周期 | `clean` `validate` `compile` `test` `package` `verify` `install` `site` `deploy`，可对整个反应堆或单个模块运行（`-pl`） |
| 插件目标 | 每个模块实际使用的构建插件及其全部目标，按 `Plugins › <前缀>` 分组，运行时使用完整坐标 |
| 自定义目标 | “Run Maven Goal…” 输入任意目标和参数 |
| 测试 | 运行模块、测试类或单个方法，读取 Surefire/Failsafe 的 JUnit XML 报告 |
| 诊断 | 把编译输出中的 `[ERROR]`/`[WARNING] 文件:[行,列]` 显示为问题 |
| 依赖 | 显示整个反应堆或单个模块的依赖树（含作用域和传递依赖），反应堆内未安装的兄弟模块也能解析 |
| 同步 | `pom.xml`、`.mvn/maven.config`、`.mvn/extensions.xml` 变化后重新读取项目 |

## 设置和构建选项

**插件设置**（设置 → 插件 → Maven →「设置」，也显示在「构建、执行、部署 › Maven」页）：

| 设置 | 说明 |
| --- | --- |
| Maven executable | 要运行的 `mvn`。留空时按下面「依赖的程序」的顺序查找；填写后总是使用这个程序，即使项目里有 `mvnw`。修改后构建模型自动重新读取 |

**构建选项**（构建窗口 →「构建选项」），只对当前工作区生效：

| 选项 | 传给 Maven 的内容 |
| --- | --- |
| JDK (JAVA_HOME) | 运行 Maven 的 JDK，设为环境变量 `JAVA_HOME` |
| Profiles | `-P <profile>`；候选值从反应堆、父 POM 和 settings.xml 读取，也可以手动补充 |
| Settings File | `-s <文件>` |
| Local Repository | `-Dmaven.repo.local=<目录>` |
| Offline | `-o`，声明为工具栏开关 |
| Skip Tests | `-DskipTests`，声明为工具栏开关 |

JDK 是构建选项而不是插件设置，因为契约 v0 的插件设置不能向构建命令注入环境变量。

## 新建项目

插件在新建项目窗口提供两个条目：

- **Maven**：生成 Maven Java 项目，可选 Java release（8、11、17、21、25）、示例代码、GroupId 和 ArtifactId。
- **Maven Archetype**：用内置原型或自定义原型，由 `mvn` 生成项目。

模板在 `io.github.lithe-idea.maven-build/templates/java/`。生成的项目需要另装 Java 语言支持插件才有代码智能。

## 依赖的程序

插件不带 Maven，按以下顺序查找：

1. 插件设置 Maven executable（填写后不再往下找；路径不存在时直接报错，不回退）；
2. 项目里的 Maven Wrapper（`mvnw`，需要 `.mvn/wrapper/maven-wrapper.properties`）；
3. 环境变量 `MAVEN_HOME` 下的 `bin/mvn`；
4. `PATH` 里的 `mvn`。

要求 Maven 3.6.3 或更新版本、Java 8 或更新版本。没有 Maven 的话可以先安装：

```bash
brew install maven
```

## 工作方式

读取项目和依赖都是 `plugin.json` 里声明的 Maven 命令，由 Lithe 运行：

- Lithe 用 `-Dmaven.ext.class.path` 给你的 Maven 加载随包提供的 `lib/lithe-maven-extension.jar`
  （源码在 `extension/`），运行一个没有默认绑定的阶段 `pre-clean`。
- 读取项目时，扩展在 Maven 读完反应堆后把模块、内容根、插件目标和 Profiles 写成 `litheBuildModel` 文档
  （`-Dlithe.modelOutput=${outputFile}`）。
- 读取依赖时，扩展把依赖树写成 `litheDependencies` 文档（`-Dlithe.dependenciesOutput=${outputFile}`）。
  选中模块时用 `-Dlithe.dependenciesProject` 只导出这个模块，否则导出反应堆里的每个模块。
- 输出文件由 Lithe 在这个工作区的插件状态目录（插件包之外的可写目录）里分配，Maven 退出后读取并删除。
- 读取项目和依赖不会编译或打包，也不会生成 `target/`；解析依赖时只读 POM，不下载依赖 jar。
- 没有 Lithe 的属性时扩展什么都不做；插件目录在运行时保持只读。

读取项目会执行 POM 中声明的 Maven 扩展和插件，首次读取还可能联网下载插件描述，所以这两条命令都声明了
`executesProjectCode` 和 `mayUseNetwork`，Lithe 会先确认你信任这个工作区。

## 安装

1. 从 [Releases](https://github.com/Lithe-IDEA/lithe-plugin-maven/releases) 下载 `maven-build-<版本>-darwin.zip`。
2. 在 Lithe 中打开 **设置 → 插件**，点击页面标题栏右侧的 **从文件安装…**，选中这个 zip，核对 SHA-256 和权限后确认。

一个包同时支持 Apple Silicon 和 Intel Mac。

## 诊断日志

插件没有自己的进程，也不发送 `lithe/log`。Maven 命令由 Lithe 启动，完整输出显示在构建输出里；
模型和依赖树文档属于工作流数据，不写进日志。排障时使用 Lithe 的日志窗口和诊断导出，
不要开启输出源码、凭据、环境变量或协议正文的日志。

## 开发

```text
io.github.lithe-idea.maven-build/plugin.json   # 插件描述，目录名必须等于插件 id
io.github.lithe-idea.maven-build/icons/        # 构建窗口、工具栏和新建项目图标（来源见 NOTICE）
io.github.lithe-idea.maven-build/templates/    # 新建项目的文件模板（Maven 项目），打包时检查路径是否存在
extension/                                     # Maven 核心扩展（Java 8），导出模型和依赖树
test/fixtures/reactor/                         # 三个模块的示例反应堆，供集成测试使用
test/fixtures/fake-maven.mjs                   # 假 Maven，检查集成测试失败时的报告
scripts/package.mjs                            # 构建扩展并打包成 dist/maven-build-<版本>-darwin.zip
scripts/test.mjs                               # 用真实 Maven 运行清单里的模型、依赖和测试命令并检查输出
scripts/test-process.mjs                       # 进程超时、中断和清理回归，不需要 Maven
scripts/verify-scripts.mjs                     # 在临时副本里打包和测试，检查已有包和宿主 bundle 不被改动
.github/workflows/ci.yml                       # PR 时打包、用 Maven 3.6.3 和最新版测试，并用 Lithe Store 的规则检查
.github/workflows/release.yml                  # 推送 v<版本> 标签时发布 GitHub Release
docs/releases/v<版本>.md                       # 该版本的变化，发布时并入 Release 说明（可选）
```

本地需要 Node.js 22+、JDK（CI 用 17；扩展按 Java 8 编译）、Maven 和 `zip`。

```bash
node scripts/package.mjs
node scripts/test.mjs
```

`MVN=/path/to/mvn` 可为打包和测试指定 Maven 版本，`JAVA_HOME` 指定运行 Maven 的 JDK（CI 用 Maven 3.6.3 加 Java 8
检查最低版本）。脚本在 macOS/Linux 上运行；每次 Maven 调用默认限时 120 秒，可用 `LITHE_MAVEN_TIMEOUT_MS` 调整
（1–600000 毫秒）；`zip` 限时 30 秒。到期或收到 SIGINT/SIGTERM 时，脚本会终止本次启动的进程组，
500 毫秒后升级为 SIGKILL，再最多等待 2 秒并报告清理失败。每个命令和检查的耗时、结果会写入
`.artifacts/test-stability/` 的 JSON、JUnit XML 和 HTML；`LITHE_REPORT_DIR` 可以指定其他报告目录。
`LITHE_CAPTURE_DIR=<目录>` 会把读到的模型和依赖文档另存一份（工作区路径写成 `${workspace}`），供 Lithe 的 Core 测试使用。

Node 子进程回归不需要 Maven 或联网，覆盖拒绝 SIGTERM 的子孙进程、父进程先退出、异常退出和中断清理：

```bash
node scripts/test-process.mjs
```

要验证打包和测试前后已有插件、ZIP 和宿主 bundle 的文件清单及 SHA-256 不变，可运行：

```bash
node scripts/verify-scripts.mjs --host-bundle /Applications/Lithe.app
```

`--host-bundle` 可以重复传入，以同时检查其他宿主实例。该验证器在临时源码副本中重新打包并测试，
保留原有 `dist/`，将新包、前后清单和逐项计时保存在 `.artifacts/script-verification/run-*/`，最后清理临时目录。
这只验证本次脚本工作流；宿主 UI 的实机验收需要另外执行。

本地开发时，用 **设置 → 插件 → 从文件安装…** 选择 `dist/maven-build-<版本>-darwin.zip` 安装。

`plugin.json` 的完整格式见 Rust Core 仓库里的
[插件契约](https://github.com/Lithe-IDEA/Lithe-core-rust/blob/main/shared/plugins/contract/v1/README.md)
和[构建工具接入指南](https://github.com/Lithe-IDEA/Lithe-core-rust/blob/main/shared/plugins/guides/build-tool-integration.md)。

## 发布新版本

1. 按语义化版本同时修改 `plugin.json` 和 `extension/pom.xml` 的版本（打包脚本会检查两者一致）：只修 Bug 升修订号，
   加功能升次版本号，有不兼容的变化升主版本号。合入 `main`。
2. 推送同名标签：

   ```bash
   git tag v0.0.1 && git push origin v0.0.1
   ```

3. `Release` 工作流会核对标签与版本、打包、测试并发布 Release（说明里附 SHA-256）。`release.yml` 里
   `PRERELEASE_TAGS` 列出的版本会标为预发布版本：目前是只能装进新版桌面端的 `v0.0.1`。
4. Lithe Store 的自动追踪会发现稳定版本并开合并请求，合并后用户即可在 Lithe 中更新。自动追踪会跳过预发布版本；
   需要收录时在 Store 中手工提交条目，并使用已发布 ZIP 的实际 SHA-256。

## 许可

[Apache-2.0](LICENSE)。图标来自 IntelliJ Platform，见 [NOTICE](NOTICE)。
