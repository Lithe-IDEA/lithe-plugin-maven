# Maven for Lithe

为 [Lithe](https://github.com/1lck/Lithe-IDEA) 提供 Maven 项目支持：识别 `pom.xml`，在 Maven 工具窗口里列出
模块、生命周期和插件目标，运行任务和测试，查看依赖树。
这是一个**社区插件**，用 Lithe 的声明式构建系统接口（插件 API 3 的 `buildSystems`）实现，
除了下面说的 Maven 扩展 jar 和读取项目用的 Java 后端，没有任何宿主代码。

> English: Maven support for Lithe through the declarative `buildSystems` plugin API — project tree, lifecycle and
> plugin goals, profiles, test runs with JUnit reports, compiler diagnostics and the dependency tree. It runs your own
> Maven with a small bundled core extension that only reads the reactor, from a Java build provider that keeps the
> result in a snapshot, so reopening a project does not run Maven again until a POM or setting changes.

> **试用版**：0.1.0 作为预发布版本提供，需要基于 Lithe `preview`、包含 `buildSystems` 插件接口及原生构建窗口支持的开发版。宿主支持 PR 合并前，请使用包含该改动的开发构建。

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

Lithe Store 的 Maven 条目合并后，才可在上述兼容开发版中打开 **设置 → 插件 → 插件市场**，搜索 “Maven”，安装并按提示重启。
Lithe 已预置 Lithe Store 社区插件源；如果你之前移除了它，先在 **⚙ → 管理插件仓库…** 中重新添加
`https://github.com/Lithe-IDEA/Lithe-store`。

一个包同时支持 Apple Silicon 和 Intel Mac；Lithe for Windows 支持插件市场后会增加 Windows 包。

## 开发

```text
io.github.lithe-idea.maven-build/plugin.json   # 插件描述，目录名必须等于插件 id
io.github.lithe-idea.maven-build/icons/        # 工具窗口和工具栏图标（来源见 NOTICE）
extension/                                     # Maven 核心扩展，导出模型和依赖树
backend/                                       # 构建后端（Java 8，Lithe 插件 SDK），快照和 Maven 运行
sdk.lock.json                                  # 打进后端的 Lithe Java 插件 SDK：版本、下载地址、SHA-256
test/fixtures/reactor/                         # 三个模块的示例反应堆，供集成测试使用
scripts/package.mjs                            # 构建扩展并打包成 dist/maven-build-<版本>-darwin.zip
scripts/test.mjs                               # 用真实 Maven 经后端读取项目、运行清单里的命令并检查输出
scripts/test-backend.mjs                       # 用假 Maven 计数，检查快照命中、失效、刷新、合并运行和取消
.github/workflows/ci.yml                       # PR 时打包、用 Maven 3.6.3 和最新版测试，并用 Lithe Store 的规则检查
.github/workflows/release.yml                  # 推送 v<版本> 标签时发布 GitHub Release
```

本地需要 Node.js 22+、JDK（CI 用 17；扩展和后端都按 Java 8 编译，后端运行只需 Java 8）和 Maven（还需要 `zip`）。

后端打进 Lithe 的 Java 插件 SDK。打包脚本按 `sdk.lock.json` 下载 SDK JAR 到 `.cache/sdk/`，SHA-256 不符就拒绝。
SDK 的第一个版本（`sdk-java-v0.1.0`）发布前，锁文件还没有 SHA-256，需要从
[1lck/Lithe-IDEA#1379](https://github.com/1lck/Lithe-IDEA/pull/1379) 的分支自行构建，再用 `LITHE_SDK_JAR` 指定：

```bash
mvn -B -f sdk/java/pom.xml verify
```

（在 Lithe-IDEA 仓库里运行，产出 `sdk/java/plugin-sdk/target/lithe-plugin-sdk-java-0.1.0.jar`。）
锁文件有 SHA-256 后，`LITHE_SDK_JAR` 指向的 JAR 也必须与之相同；发布（`--expect-tag`）不接受 `LITHE_SDK_JAR`。

```bash
LITHE_SDK_JAR=/path/to/lithe-plugin-sdk-java-0.1.0.jar node scripts/package.mjs
```

SDK 发布后直接运行：


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

`plugin.json` 的完整格式见 Lithe 的
[插件契约](https://github.com/1lck/Lithe-IDEA/blob/preview/shared/contracts/plugins/v1/README.md)
和 Lithe Store 的[构建工具接入指南](https://github.com/Lithe-IDEA/Lithe-store/blob/main/docs/build-tool-integration.md)。

## 发布新版本

1. 同时修改 `plugin.json`、`extension/pom.xml` 和 `backend/pom.xml` 的版本（例如 `0.2.0`；打包脚本会检查三者一致），
   确认 `sdk.lock.json` 已填入正式发布的 SDK 地址和 SHA-256，合入 `main`。
2. 推送同名标签：

   ```bash
   git tag v0.2.0 && git push origin v0.2.0
   ```

3. `Release` 工作流会核对标签与版本、打包、测试并发布 Release（说明里附 SHA-256）。首个 `v0.1.0` 会自动标为预发布版本，需要包含 `buildSystems` 接口的 Lithe 开发版。
4. Lithe Store 的自动追踪会发现稳定版本并开合并请求，合并后用户即可在 Lithe 中更新。自动追踪会跳过预发布版本；`v0.1.0` 需要在 Store 中手工提交条目，并使用已发布 ZIP 的实际 SHA-256。

## 许可

[Apache-2.0](LICENSE)。图标来自 IntelliJ Platform，见 [NOTICE](NOTICE)。
