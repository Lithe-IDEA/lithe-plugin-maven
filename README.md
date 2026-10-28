# Maven for Lithe

为 [Lithe](https://github.com/1lck/Lithe-IDEA) 提供 Maven 项目支持：识别 `pom.xml`，在 Maven 工具窗口里列出
模块、生命周期和插件目标，运行任务和测试，查看依赖树。
这是一个**社区插件**，用 Lithe 的构建系统接口（插件 API 3 的 `buildSystems` 和 `buildProviders: 7`）实现，
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
- 读取模型和依赖不会编译或打包，也不会生成 `target/`；只有用户显式执行任务或测试才运行构建。解析依赖时只读 POM，不下载依赖 jar。
- 没有 Lithe 的属性时扩展什么都不做；插件目录在运行时保持只读。

读取项目会执行 POM 中声明的 Maven 扩展和插件，首次读取还可能联网下载插件描述，所以 Lithe 会先确认你信任这个工作区。

## 原生任务与测试（开发中）

当前源码把生命周期、模型中的插件目标、自定义目标以及测试统一交给同一个工作区
Maven 后端执行。清单里这些入口的命令模板已删除，宿主仅传任务 ID、工程选择、
生效选项和原始参数数组。Maven 自己读取工程并执行；插件没有重写 POM、工程模型、
依赖求解或 Surefire。普通任务的 Skip Tests 仍按任务语义生效；显式测试动作不会
被这个工具栏开关跳过。

嵌套工作区使用宿主提供的工作区相对路径，执行前按当前 Maven 扩展模型重新确认
模块名称、目录及插件目标，插件目标不能借一个伪造 properties.goal 运行。输出通过
Provider 进度传回，最终结果保留非零退出码；取消或宿主退出会结束本次 Maven 与子进程。
总输出最多 8 MiB，超限会明确失败并停止工具。报告和编译输出仍写用户工程的 target；
快照写宿主提供的工作区状态目录，插件安装目录保持只读。

测试的类和方法选择映射到 Surefire 的公开接口，特殊模式明确拒绝，不把一个精确
请求扩大成通配匹配。使用 -am 构建依赖模块时，它们可以没有所选测试；执行结束后
还检查当前模块/反应堆是否产生所选测试的新报告，避免“不存在的方法 + 成功空结果”
或旧报告被当成测试通过。完整结果仍由宿主既有 JUnit XML 解析器展示。

插件通过 buildModelInputs 1 显式公开规范化项目、目标、内容目录及 profiles/offline/
skipTests 的生效值；共享内容不包含任务、运行计划、settings.xml 内容或私有函数。
这为后续 Java 协作提供数据入口，不表示 Java/JDT、旧 MavenService、Agent 或设置
已经完成迁移。

**发布前依赖尚未完成：** 该源码需要包含原生执行处理器的新版 Java SDK，正式
sdk.lock.json 仍锁定已有发布物，没有伪造新版本/校验值或放松 SHA-256 检查。
必须先发布配套 SDK 并更新锁定值，之后才可走正式 package.mjs 和 Release 流程。
当前只使用显式的本地开发构建验证，不应把它作为可发布包。

```bash
node scripts/package-development.mjs --sdk-jar /absolute/path/to/development-sdk.jar
# 上一步打印独立目录，或读取 .artifacts/native-development/latest.json 的 package。
export LITHE_TEST_PACKAGE=/absolute/path/printed/above/io.github.lithe-idea.maven-build
node scripts/test-backend.mjs
node scripts/test-native.mjs
node scripts/test.mjs
# 可选：在包含 buildModelInputs 的当前宿主 Core 上完整验证计划、报告及模型共享。
LITHE_CORE_LIBRARY=/absolute/path/to/liblithe_core.dylib node scripts/test-native-core.mjs
```

开发构建在 .artifacts/native-development/run-* 的独立源码副本中编译，记录显式 SDK
的 SHA-256 和完整包文件清单；不生成发行 ZIP、不写 dist/，不改 sdk.lock.json。
这些工作区产物不能跨工作树共享，不是产品运行缓存。测试保留每项 JSON/HTML/JUnit
耗时并清理所有本次进程。真实 Core 验证还需要 Python 3 的标准 ctypes 模块，不会安装
Python 包。Windows 产品尚未接入本契约，本地 Java 21 验证也不能代替 Java 8 实机验收。

公开接口依据：[Maven CLI](https://maven.apache.org/ref/3-LATEST/maven-embedder/cli.html)、
[Surefire 的测试选择](https://maven.apache.org/surefire/maven-surefire-plugin/test-mojo)。

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

后端打进 Lithe 的 Java 插件 SDK。打包脚本按 `sdk.lock.json` 下载正式发布的 SDK JAR 到 `.cache/sdk/`，SHA-256 不符就拒绝。

如需本地调试，可用 `LITHE_SDK_JAR` 指向一个 JAR；正式锁定的 SHA-256 仍会校验该文件，发布（`--expect-tag`）不接受这个覆盖变量：

```bash
LITHE_SDK_JAR=/path/to/lithe-plugin-sdk-java-0.1.0.jar node scripts/package.mjs
```

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

合并前验证新版 SDK 源码时，可以手动运行 CI 的 `workflow_dispatch`，传入
`sdk-revision`（`lithe-plugin-docs` 的完整提交 SHA）。该模式只构建隔离的开发包，
在 Java 8 上执行快照、原生任务/取消/清理与真实 Maven 反应堆测试，覆盖当前 Maven
和最低支持的 3.6.3。报告包含 SDK JAR 哈希和包文件清单；不会修改 `sdk.lock.json`、
生成 `dist/` 或发布工件。普通 PR、主分支和 Release 继续使用正式 SDK 的地址与哈希
校验，开发验证通过不能代替正式打包。

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

## 原生配置（协议 7，开发中）

通用的构建系统设置页通过 `lithe/build/configuration` 读取 Maven 实际选中的安装级和用户级
settings.xml，显示非敏感的 localRepository、activeProfiles 原值及实际生效值。打开页面只读；
只有显式开启覆盖并保存，宿主才修改所列来源。安装来源以及与安装文件相同的用户来源始终只读。
保存使用源字节哈希防止覆盖外部编辑，保留其他内容、注释、权限和凭据，再由 Maven 重新读取
生效值。命令行覆盖值单独显示，不能将保存的原值直接当成生效值。

扩展观察 Maven 官方的 SettingsBuildingRequest/Result 事件，不重新实现配置优先级；
会话执行请求提供最终 CLI 值。配置读取在 reactor 导入前结束，因此损坏 POM 不会阻止查看
原生配置。整个 XML 不跨 Provider RPC，服务器/代理密码等字段既不导出也不记录。工具探针
只在已分配的工作区状态创建临时目录，调用结束清理；app 和插件安装资源不变。

模型缓存版本 2 只存实际上游选中的 settings/toolchains 的 URI 与字节哈希。它在读取和发布
结果时校验来源，在下一次请求复用缓存前再次核对；修改、删除或换向选中来源即使没有 POM
事件也会使缓存失效。旧缓存只是可重建结果，版本不匹配即重新读取。来源哈希不是可跨工作树
复用的构建身份，不复制缓存或探针目录。用户原生文件只由宿主明确保存流程写入。

显式运行隔离测试（不会使用或修改开发者真实 settings）：

```bash
LITHE_TEST_JAVA=/absolute/java LITHE_TEST_MAVEN=/absolute/mvn \
LITHE_CORE_LIBRARY=/absolute/liblithe_core.dylib node scripts/test-configuration.mjs
```

此能力依赖尚待正式发布、锁定的配套 SDK 和宿主。源码的开发包、Core 和原生 macOS
测试通过不等于完成整个 #1495 阶段 D；旧配置数据、其余 Run/Test/Debug 和 Windows
消费者仍需完成各自迁移并删除旧路径。

`workflow_dispatch` 的开发 SDK lane 可额外指定 40 位 `core-revision`，构建该宿主 Core 后运行
原生配置及真实构建/精确测试的集成；SDK、Core 都按明确提交选取，不修改正式锁定文件。

构建选项要求 `buildSystems: 3`，按每个检测到的 reactor 根独立保存；Profiles、settings 文件、仓库、离线和跳过测试不再共享同一个工作区值。宿主升级作用域时须先复制并读回旧 Build 选项，保留已有实例覆盖和显式空选择后再删除旧表示。旧 MavenService 的数据迁移及消费者删除仍属于阶段 D 后续验收，不由这个清单变更代替。

工具解析要求 `executableResolution: 2`，通过宿主已有 `project-maven` / `project-maven-jdk` 注册表读取 `.lithe/run/local.json` 的当前实例选择，复用 Run 的配置链。宿主把选中的 Maven 路径以及有限的运行时环境（含 JAVA_HOME/PATH）交给 Provider；Provider 进程自身的 JDK 不等于 Maven 所用的 JDK。显式选择失败不能独立改用其他 Maven；被动导入不执行 Wrapper 或探测版本。

只读配置探针观察 Maven 官方完成默认值填充后的 `MavenExecutionRequest`：先保留所选 settings、Profiles 和仓库事实，再将这次探针自己的仓库管理器隔离到临时 `configuration-*/repository`，在工程导入前退出。仅在 `afterSessionStart` 导出会过晚，可能已经创建用户仓库。读取、保存后的回读与 CLI 覆盖均不得创建实际仓库；临时目录统一清理。真实模型、依赖和构建使用原仓库，不采用此探针隔离。
