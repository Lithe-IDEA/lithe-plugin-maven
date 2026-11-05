# Maven for Lithe

为 [Lithe](https://github.com/1lck/Lithe-IDEA) 提供 Maven 项目支持：识别 `pom.xml`，在 Maven 工具窗口里列出
模块、生命周期和插件目标，运行任务和测试，查看依赖树。
这是一个**社区插件**，用 Lithe 的声明式构建系统接口（插件 API 3 的 `buildSystems`）实现，
除了下面说的 Maven 扩展 jar 和读取项目用的 Java 后端，没有任何宿主代码。

> English: Maven support for Lithe through the declarative `buildSystems` plugin API — project tree, lifecycle and
> plugin goals, profiles, test runs with JUnit reports, compiler diagnostics and the dependency tree. It runs your own
> Maven with a small bundled core extension that only reads the reactor, from a Java build provider that keeps the
> result in a snapshot, so reopening a project does not run Maven again until a POM or setting changes.

> **预览版**：0.1.2 需要提供结构化插件诊断（`litheLogging: 1`）的 Lithe `preview` 开发版。稳定版 Lithe 0.6.7 没有 `litheLogging: 1`，能安装但无法启动构建后端，请继续使用 Lithe Store 中的 0.1.1。0.1.2 以预发布版本提供，暂不收录 Lithe Store。

## 功能

| 功能 | 说明 |
| --- | --- |
| 识别项目 | 工作区最外层的 `pom.xml` 是构建根，`<modules>` 组成模块树 |
| 生命周期 | `clean` `validate` `compile` `test` `package` `verify` `install` `site` `deploy`，可对整个反应堆或单个模块运行（`-pl`） |
| 插件目标 | 每个模块实际使用的构建插件及其全部目标，按 `Plugins › <前缀>` 分组，运行时使用完整坐标 |
| 自定义目标 | “Run Maven Goal…” 输入任意目标和参数 |
| 选项 | Profiles（从反应堆、父 POM 和 settings.xml 读取，可手动补充）、Settings 文件、本地仓库；工具栏开关 Offline（`-o`）和 Skip Tests（`-DskipTests`） |
| 测试 | 运行模块、测试类或单个方法，读取 Surefire/Failsafe 的 JUnit XML 报告 |
| 诊断 | 把编译输出中的 `[ERROR]`/`[WARNING] 文件:[行,列]` 显示为问题 |
| 依赖 | 按模块显示依赖树（含作用域和传递依赖），反应堆内未安装的兄弟模块也能解析；一次 Maven 运行读出全部模块，展开模块不再各跑一次 |
| 快照 | 项目和依赖保存在工作区状态目录，重新打开项目时直接显示；POM、`.mvn/` 配置、settings.xml、选项或 Maven/Java 变化后才重新运行 Maven，Reload 总是重新运行 |
| 同步 | `pom.xml`、`.mvn/maven.config`、`.mvn/extensions.xml` 变化后重新读取项目 |

## 新建项目

插件在 **New Project** 窗口提供两个条目：

- **Maven**：生成 Maven Java 项目，可选示例代码，可设置 GroupId 和 ArtifactId。
- **Maven Archetype**：用内置原型或自定义原型，由 `mvn` 生成项目。

模板在 `io.github.lithe-idea.maven-build/templates/java/`，从 Java Support 插件移来。
生成的项目需要 Java Support 提供 Java 语言支持。

## 依赖的程序

插件不带 Maven，使用你项目或电脑上的 Maven，按以下顺序查找：

1. 项目里的 Maven Wrapper（`mvnw`，需要 `.mvn/wrapper/maven-wrapper.properties`）；
2. 环境变量 `MAVEN_HOME` 下的 `bin/mvn`；
3. `PATH` 里的 `mvn`。

要求 Maven 3.6.3 或更新版本、Java 8 或更新版本（读取项目的后端也用 Lithe 找到的这个 Java 运行）。没有 Maven 的话可以先安装：

```bash
brew install maven
```

## 工作方式

读取项目和依赖由插件的构建后端 `lib/lithe-maven-backend.jar`（源码在 `backend/`）负责。Lithe 在第一次需要
项目模型时用 Java 启动它，工作区关闭时结束它：

- 需要重新读取时，后端用 `-Dmaven.ext.class.path` 给你的 Maven 加载随包提供的
  `lib/lithe-maven-extension.jar`（源码在 `extension/`），运行一个没有默认绑定的阶段 `pre-clean`。
  扩展在 Maven 读完反应堆后，把模块、内容根、插件目标和 Profiles 写成 Lithe 的 `litheBuildModel` 文档，
  同时把**每个模块**的依赖树写成 `litheDependencies` 文档（`-Dlithe.modelOutput` / `-Dlithe.dependenciesOutput`）。
- 结果连同输入指纹保存在 Lithe 给这个工作区的状态目录里的 `maven-snapshot.json`。指纹包含根目录下所有
  `pom.xml`（跳过 `target/`、`.git/`、`node_modules/`）、`.mvn/maven.config`、`extensions.xml`、`jvm.config`、
  Wrapper 配置、用户和全局 `settings.xml`、`-s` 指定的文件、选项、Maven 可执行文件及其修改时间、Java 版本和扩展版本。
  指纹不变时，重新打开项目或展开模块直接用快照，不启动 Maven。
- 同时到达的请求共用一次 Maven 运行；全部请求都取消后才结束 Maven。运行期间 POM 被修改时，本次结果照常显示但不保存。
- 指纹看不到的变化（例如根目录外的父 POM、远程仓库里更新的 SNAPSHOT）请点 Reload，它总是重新运行 Maven。
- 不会编译或打包，也不会生成 `target/`；解析依赖时只读 POM，不下载依赖 jar。
- 没有 Lithe 的属性时扩展什么都不做；插件目录在运行时保持只读。

读取项目会执行 POM 中声明的 Maven 扩展和插件，首次读取还可能联网下载插件描述，所以 Lithe 会先确认你信任这个工作区。

## 安装

稳定版 Lithe 在 **设置 → 插件 → 插件市场** 中搜索 “Maven”，安装 0.1.1 并按提示重启。
0.1.2 预览版请从 [Releases](https://github.com/Lithe-IDEA/lithe-plugin-maven/releases) 下载 `maven-build-0.1.2-darwin.zip`，
在 **设置 → 插件** 的 ⚙ 菜单中选择 **从磁盘安装插件…**；Store 无法区分不提供 `litheLogging: 1` 的稳定版宿主，所以暂不收录。
Lithe 已预置 Lithe Store 社区插件源；如果你之前移除了它，先在 **⚙ → 管理插件仓库…** 中重新添加
`https://github.com/Lithe-IDEA/Lithe-store`。

一个包同时支持 Apple Silicon 和 Intel Mac；Lithe for Windows 支持插件市场后会增加 Windows 包。

## 诊断日志

0.1.2 起要求宿主提供 `litheLogging: 1`。SDK 通过类型化的 `lithe/log` 通知，把 Maven provider
操作交给宿主 v2 目录和统一 writer，写入当前会话的 `plugins/io.github.lithe-idea.maven-build/host` 通道。
关键成功终态为 INFO、失败终态为 ERROR，包含单调耗时和稳定分类；取消不记失败。
缓存保存失败或读取中项目变化属于可恢复降级，使用 WARN `degraded`，不记录异常正文、路径或环境值。
模型快照继续属于 `${workspaceStateDir}`，不会搬到会话日志目录。

Maven 失败由 SDK 的请求边界记录一次，provider 不再重复打印构建输出。
完整构建输出仍属于 Build Output，模型/依赖树 JSON 属于工作流数据。
后端从 `vendor/` 直接编译官方 Java SDK 的结构化日志实现，使用 `lithe/log` 发送稳定分类、
单调耗时和操作身份；原始异常与构建 tail 不进入日志。`sdk.lock.json` 记录真实上游基线、
本轮尚未发布的源代码改动及每个文件的 SHA-256，没有沿用或伪造旧发版 JAR 的校验值。

排障时使用 Lithe 的独立日志窗口和诊断导出；不要开启输出源码、凭据、环境变量或协议正文的日志。
Windows 的日志实现仍为 v1，未声明 Windows v2 已完成验收。

## 开发

```text
io.github.lithe-idea.maven-build/plugin.json   # 插件描述，目录名必须等于插件 id
io.github.lithe-idea.maven-build/icons/        # 工具窗口、工具栏和新建项目图标（来源见 NOTICE）
io.github.lithe-idea.maven-build/templates/    # 新建项目的文件模板（Maven 项目），打包时检查路径是否存在
extension/                                     # Maven 核心扩展，导出模型和依赖树
backend/                                       # 构建后端（Java 8，Lithe 插件 SDK），快照和 Maven 运行
vendor/                                        # 官方 Java SDK 生产源码和许可证（不带测试、JAR、target）
sdk.lock.json                                  # SDK 上游基线、本轮改动身份、逐文件 SHA-256
scripts/sdk/verify-sdk.mjs                      # 编译前拒绝漂移、遗漏或额外 SDK 文件
test/fixtures/reactor/                         # 三个模块的示例反应堆，供集成测试使用
scripts/package.mjs                            # 构建扩展并打包成 dist/maven-build-<版本>-darwin.zip
scripts/test.mjs                               # 用真实 Maven 经后端读取项目、运行清单里的命令并检查输出
scripts/test-backend.mjs                       # 用假 Maven 计数，检查快照命中、失效、刷新、合并运行和取消
.github/workflows/ci.yml                       # PR 时打包、用 Maven 3.6.3 和最新版测试，并用 Lithe Store 的规则检查
.github/workflows/release.yml                  # 推送 v<版本> 标签时发布 GitHub Release
docs/releases/v<版本>.md                       # 该版本的变化，发布时并入 Release 说明（可选）
```

本地需要 Node.js 22+、JDK（CI 用 17；扩展和后端都按 Java 8 编译，后端运行只需 Java 8）和 Maven（还需要 `zip`）。

后端把固定在 `vendor/lithe-plugin-sdk-java/src/main/java/` 的 SDK 生产源码一同按 Java 8 编译。
打包前先校验 `sdk.lock.json`，缺失、改动或额外未登记文件都会拒绝；只读源码不共享可变构建状态。
不再从网络下载 SDK JAR，也不接受 `LITHE_SDK_JAR` 替换构建输入。
SDK 以 Apache-2.0 分发，源码许可证位于 `vendor/LICENSE`，安装包附带 `SDK-LICENSE`。
如需更新，先从官方 SDK 仓库取真实生产源码，审查变更并更新基线和逐文件摘要，再执行验证。

开发或发布时直接运行：

```bash
node scripts/package.mjs
```

```bash
node scripts/test.mjs
```

后端的快照行为不需要 Maven，用假 Maven 计数验证；`LITHE_TEST_JAVA=/path/to/java` 可指定运行后端的 Java
（CI 用 Java 8 和 17 各跑一次，`test.mjs` 同样读取这个变量）：

```bash
node scripts/test-backend.mjs
```

`MVN=/path/to/mvn` 可为打包和测试指定 Maven 版本。脚本在 macOS/Linux 上运行；每次 Maven 调用默认限时 120 秒，
可用 `LITHE_MAVEN_TIMEOUT_MS` 调整（1–600000 毫秒）；`zip` 限时 30 秒。到期或收到 SIGINT/SIGTERM 时，
脚本会终止本次启动的进程组，500 毫秒后升级为 SIGKILL，再最多等待 2 秒并报告清理失败。
每个命令和检查的耗时、结果会写入 `.artifacts/test-stability/` 的 JSON、JUnit XML 和 HTML；
`LITHE_REPORT_DIR` 可以指定其他报告目录。真实 Maven 测试是依赖本机工具及 Maven 仓库的集成测试。

Node 子进程回归不需要 Maven 或联网，覆盖拒绝 SIGTERM 的子孙进程、父进程先退出、异常退出和中断清理：

```bash
node scripts/test-process.mjs
```

要验证打包/测试前后已有插件、ZIP 和宿主 bundle 的文件清单及 SHA-256 不变，可运行：

```bash
node scripts/verify-scripts.mjs --host-bundle /Applications/Lithe.app
```

`--host-bundle` 可以重复传入，以同时检查其他宿主实例。该验证器在临时源码副本中重新打包并测试，
保留原有 `dist/`，将新包、前后清单和逐项计时保存在 `.artifacts/script-verification/run-*/`，最后清理临时目录。
这只验证本次脚本工作流；宿主 UI 的实机验收需要另外执行。

在 Lithe 开发版中，通过 **设置 → 插件 → ⚙ → 从磁盘安装插件…** 选择 `dist/package/io.github.lithe-idea.maven-build`
即可安装本地开发包（发行版 Lithe 不加载未签名的开发包）。

`plugin.json` 的完整格式见 lithe-plugin-docs 里的
[插件契约](https://github.com/Lithe-IDEA/lithe-plugin-docs/blob/main/contract/v1/README.md)
和[构建工具接入指南](https://github.com/Lithe-IDEA/lithe-plugin-docs/blob/main/guides/build-tool-integration.md)。

## 发布新版本

1. 同时修改 `plugin.json`、`extension/pom.xml` 和 `backend/pom.xml` 的版本（例如 `0.2.0`；打包脚本会检查三者一致），
   确认 `sdk.lock.json` 已登记所用 SDK 源码、上游基线与全部 SHA-256，合入 `main`。
2. 推送同名标签：

   ```bash
   git tag v0.2.0 && git push origin v0.2.0
   ```

3. `Release` 工作流会核对标签与版本、打包、测试并发布 Release（说明里附 SHA-256）。`release.yml` 里 `PRERELEASE_TAGS` 列出的版本（`v0.1.0` 试用版、需要 `litheLogging: 1` 的 `v0.1.2`）会标为预发布版本。
4. Lithe Store 的自动追踪会发现稳定版本并开合并请求，合并后用户即可在 Lithe 中更新。自动追踪会跳过预发布版本；需要收录时在 Store 中手工提交条目，并使用已发布 ZIP 的实际 SHA-256。

## 许可

[Apache-2.0](LICENSE)。图标来自 IntelliJ Platform，见 [NOTICE](NOTICE)。
