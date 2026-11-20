# 功能矩阵

本页记录 Maven 插件自己的跨平台功能状态。Lithe 主仓库的功能矩阵（`shared/platform-feature-matrix/`）
只记录宿主能力，不再收录插件能力（[1lck/Lithe-IDEA#1915](https://github.com/1lck/Lithe-IDEA/pull/1915)）。
插件能力的状态、证据和验证方法在这里维护，改动插件行为的 PR 同时更新本页。

## 状态取值

沿用主仓库功能矩阵的取值：

| 字段 | 取值 |
| --- | --- |
| 实现 | `implemented` 已实现；`partial` 部分实现；`missing` 未实现；`not-applicable` 该平台不适用 |
| 验证 | `verified` 已按“验证方法”完整验收；`pending` 尚未完整验收；`not-applicable` 无需验收 |

只有按“验证方法”完整走过一遍，并写明日期、插件版本和宿主版本，才能标 `verified`。
自动化测试的结果写在“最近验证”里，不能代替验收。

从 0.2.1 起插件也发布 `maven-build-<版本>-windows.zip`（与 macOS 包逐字节相同，见 [Windows 构建与验证](windows-packaging.md)）。
Windows 一列的实现状态与 macOS 相同；Windows 上还没有做过宿主端到端测试或手工验收，所以验证都是 `pending`。
CI 在 Windows x64 上用 `mvn.cmd` 运行声明的模型、依赖和测试命令，结果见 [Windows 安装包](#windows-安装包)。

## 总览

| ID | 能力 | macOS | Windows | 最近验证 |
| --- | --- | --- | --- | --- |
| `java-maven-project` | 识别 Maven 项目：构建根、模块树、内容根和任务 | implemented / pending | implemented / pending | 2026-11-14 宿主端到端测试通过（macOS） |
| `java-maven-profiles-dependencies` | Profiles 构建选项与依赖树 | implemented / pending | implemented / pending | 2026-11-14 依赖树由宿主端到端测试覆盖（macOS） |
| `java-maven-project-settings` | Maven 调用设置：Maven 程序、工作区构建选项、`settingRegions` 搜索关键词与中文翻译 | partial / pending | partial / pending | 2026-11-18 `settingRegions`（`build`、`compiler`）、`settings.mavenExecutable`、`sections.Maven` 通过 Core `9dea6aa` 校验与 `scripts/test.mjs`；2026-11-14 Maven executable 由宿主端到端测试覆盖（macOS） |
| `java-maven-settings-reach-jdt` | Maven 构建选项作用于 Java 语言服务 | missing / not-applicable | missing / not-applicable | — |
| `java-maven-toolbar-tooltips` | 构建窗口里的 Maven 图标、工具栏开关和提示 | implemented / pending | implemented / pending | 没有自动化覆盖 |
| `maven-user-settings-editing` | 编辑用户级 `~/.m2/settings.xml` 的本地仓库 | missing / not-applicable | missing / not-applicable | — |
| `maven-project-generators` | 新建项目：Maven 和 Maven Archetype | implemented / pending | implemented / pending | 2026-11-14 宿主端到端测试通过（macOS） |
| `maven-run-applications` | 运行配置：Spring Boot 服务和 Java 应用的运行与调试 | implemented / pending | implemented / pending | 2026-11-17 宿主端到端测试（运行与断点调试）和 `scripts/test.mjs` 通过（macOS）；`scripts/test.mjs` 在 Windows CI 通过 |
| `maven-task-run-configurations` | 声明式 Maven 任务型运行配置（`Maven`）与启动前任务（`Run Maven Goal` / `运行 Maven 目标`），含中英文翻译 | implemented / pending | implemented / pending | 2026-11-18 通过 Core `9dea6aa`（`runConfig.configurationTypes` / `runConfig.editorForm`）、`scripts/test.mjs` 及宿主 Viva 无头渲染预览（macOS） |

前六条从主仓库迁移；`maven-project-generators` 是本仓库新增，用来记录随测试一起迁来的新建项目测试；`maven-run-applications` 随运行配置一起新增（[1lck/Lithe-IDEA#2074](https://github.com/1lck/Lithe-IDEA/issues/2074)）；`maven-task-run-configurations` 记录按新版插件 UI 契约新增的声明式 Maven 任务运行配置与启动前任务。

## java-maven-project

**能力**：工作区最外层的 `pom.xml` 是构建根，`<modules>` 组成模块树；随包的 Maven 扩展在 Maven 读完反应堆后导出模块、
内容根、生命周期和每个模块实际使用的插件目标；`pom.xml`、`.mvn/maven.config`、`.mvn/extensions.xml` 变化后重新读取。

**证据**：`io.github.lithe-idea.maven-build/plugin.json` 的 `buildSystems[0]`（`detection`、`model`、`tasks`、`sync`）、
`extension/`、`scripts/test.mjs`、`e2e/host/desktop/internal/workbench/official_maven_plugin_e2e_test.go`。

**依赖的宿主能力**：`buildSystems`（构建窗口识别实例、运行模型命令并解码 `litheBuildModel`、运行任务）。

**最近验证**：2026-11-14，macOS arm64，插件 0.0.1 发布包，宿主 `059d878d4`，`node scripts/e2e-host.mjs`：`TestOfficialMavenPluginBuildsAReactor` PASS（8.76s）。测试装上插件包，对两模块反应堆检查模型（两个模块挂在聚合项目下、模型里有插件目标）、运行 `compile` 并检查生成的 class 文件，最后确认插件安装目录的摘要不变。没有覆盖嵌套聚合、内容根、`-pl` 单模块任务、文件变化后的自动重读和插件目标分组，需要手工验收。

**验证方法**（macOS）：

1. 安装 `dist/maven-build-<版本>-darwin.zip`，打开单模块、多模块（含嵌套聚合）和非标准源码目录的项目。
2. 构建窗口的模块树、父子关系和内容根与 `pom.xml` 一致；插件目标按 `Plugins › <前缀>` 分组。
3. 对整个反应堆和单个模块（`-pl`）运行 `compile`、`test`、`package`，输出和编译问题显示正确。
4. 修改 `pom.xml` 或 `.mvn/maven.config` 后项目重新读取；读取模型和依赖时不生成 `target/`。
5. 整个过程中插件安装目录不被写入。

**迁移来源**：主仓库 `shared/platform-feature-matrix/features/java-maven-project.json`（1lck/Lithe-IDEA@b582e206a）。
原条目记录的是旧 Swift 宿主上 Maven Provider、JDT 构建路径和运行配置生成的迁移过程；Maven 插件已按契约 v0 改为声明式
`buildSystems` 并从 0.0.1 重新发布，没有后端，原条目里的 Provider、JDT 链路和运行配置内容不再适用。
JDT 侧的项目导入归 Java Support 插件维护。

## java-maven-profiles-dependencies

**能力**：构建选项 Profiles 传 `-P <profile>`，候选值从反应堆、父 POM 和 `settings.xml` 读取，也可以手动补充；
随包 Maven 扩展用 Maven 自身的 `ProfileSelector` 计算应用 Lithe 覆盖前的默认 Profile（含 `.mvn/maven.config` 中的启用和禁用、`settings.xml`、条件激活以及 Maven 3.9 的 `packaging` 条件）写入 `optionDefaults.profiles`。当前反应堆实际激活的 Profile 由 Maven 项目模型提供，写入 `optionActiveChoices.profiles`，包括 `settings.xml` 和命令行同时激活的选项。扩展还输出 `optionExcludePrefixes.profiles = "-"`，支持在 Build 工具窗口树、构建选项和运行配置里勾选启用或以 `-<profile>` 显式取消默认激活 Profile；
依赖树显示整个反应堆或单个模块的依赖（含作用域和传递依赖），反应堆内未安装的兄弟模块也能解析。

**证据**：`plugin.json` 的 `buildSystems[0].options`（`profiles`）和 `buildSystems[0].dependencies`、`extension/`（`ModelExport.java`）、
`scripts/test.mjs`、宿主端到端测试里 `app` 模块依赖 `core` 的检查。

**自动化**：`node scripts/test.mjs` 在 Maven 3.6.3 和运行器版本验证原生 Profile 语义。宿主能力版本匹配时，CI 另用 `node scripts/e2e-host.mjs --suite profiles` 验证构建树、编译和重开。

**依赖的宿主能力**：`buildSystems` 的构建选项（含 `optionDefaults`、`optionActiveChoices`、`optionExcludePrefixes` 与 `presentation: "tree"` 勾选树节点）、依赖命令和 `litheDependencies` 解码。

**最近验证**：2026-11-18，macOS arm64，插件 1.0.0 打包产物，`node scripts/test.mjs` 验证了反应堆 `optionValues.profiles`、`optionDefaults.profiles`（`<activeByDefault>`）与 `optionExcludePrefixes.profiles = "-"` 的导出，以及通过 `-P -dev,prod` 切换激活模块和 `-P -dev` 取消全部默认 Profile 后模型按新 Profile 重读且 `optionDefaults` 保持不变。Maven 3.6.3 和 3.9.16 验证了项目配置与打包类型激活基线。Maven 3.9.16 还验证了当前激活集合随这两次切换分别变为 `["prod"]` 和 `[]`，以及 `settings.xml` 激活 `dev` 与命令行选择 `prod` 可以同时生效。固定宿主 `6f31984` 上的 `TestOfficialMavenPluginSwitchesActiveProfilesInBuildTree` 验证项目 Maven 配置的勾选、切换、编译、重开及已安装插件包内容校验；2026-11-14 宿主端到端测试 `TestOfficialMavenPluginBuildsAReactor` 检查了 `app` 对反应堆兄弟模块 `core` 的依赖。完整原生 UI 手工验收与大依赖树仍待执行。

**验证方法**（macOS）：

1. 在声明了多个 Profile 的项目里切换 Profile，模型和依赖树按所选 Profile 重新读取。
2. 依赖树的模块归属、顺序、作用域和传递依赖与 `mvn dependency:tree` 一致；依赖节点超过 6,000 个的项目能完整加载。
3. 依赖解析失败时显示 Maven 的真实错误。

**迁移来源**：主仓库 `shared/platform-feature-matrix/features/java-maven-profiles-dependencies.json`（b582e206a）。
原条目中“Java 导航使用 JDT 解析的依赖快照”属于 Java Support 插件，旧 MavenService 删除记录属于旧 Swift 宿主，都不再写在这里。

## java-maven-project-settings

**能力**：插件设置 Maven executable（全局，修改后重新读取模型）；构建选项 Profiles、Settings File、
Local Repository、Offline、Skip Tests，按工作区保存，加到构建、测试和读取模型的命令里。

| 平台 | 实现 | 验证 | 说明 |
| --- | --- | --- | --- |
| macOS | partial | pending | 构建选项按工作区保存，不能给单个子项目设置不同的 JDK 或选项 |
| Windows | partial | pending | 同 macOS；Windows 上按 `mvnw.cmd`、`MAVEN_HOME\bin\mvn.cmd`、`PATH` 上的 `mvn`（`mvn.cmd`）的顺序查找 Maven |

**证据**：`plugin.json` 的 `settings`（`mavenExecutable`）、`settingRegions`（`build`、`compiler`）、`buildSystems[0].invocation` 和 `buildSystems[0].options`，`l10n/zh-Hans.json`（`settings.mavenExecutable`、`sections.Maven`、`settingRegions.build`、`settingRegions.compiler`），以及 `scripts/test.mjs`；
宿主端到端测试设置 Maven executable 后检查模型重新读取和 `compile` 都运行了设置的 `mvn`。

**依赖的宿主能力**：`settings`（插件设置页和 `reload` 生效方式）、`settingRegions`（设置侧栏搜索关键词定位）、`buildSystems` 的构建选项和可执行文件候选顺序。

**实际值**：模型的 `optionDefaults` 报告 Settings File 和 Local Repository 在不传参数时 Maven 实际使用的值（用户设置文件为 `~/.m2/settings.xml`；本地仓库取本次读取的设置中的 `<localRepository>`，没有时为 `~/.m2/repository`）。宿主（Core `Lithe-core-rust#120`、`#121` 及以后、桌面 [1lck/Lithe-IDEA#2210](https://github.com/1lck/Lithe-IDEA/pull/2210)）据此显示 IDEA 式的“覆盖”复选框；旧宿主忽略这两项。2026-11-19 `scripts/test.mjs` 的 `the model reports the settings file and local repository Maven uses` 通过（macOS arm64）。

**最近验证**：2026-11-18，`plugin.json` 与 `l10n/zh-Hans.json` 经 Core `9dea6aa6eaaf830c3568b647aa7daae5018b9727` schema 与 `plugins.settings.catalog` / `plugins.declarative.settingsState` 校验，`scripts/test.mjs` 断言全部通过，并在宿主 Viva 无头设置窗口验证中文分组名与关键词检索。2026-11-14，macOS arm64，插件 0.0.1 发布包，宿主 `059d878d4`，`node scripts/e2e-host.mjs`：`TestOfficialMavenPluginBuildsAReactor` 设置 Maven executable 为一个包装脚本后，确认模型重新读取和 `compile` 都运行了它。五个构建选项、按工作区保存和 `mvnw` / `MAVEN_HOME` 的候选顺序没有自动化覆盖。

**验证方法**（macOS）：

1. Maven executable 留空时依次使用 `mvnw`、`MAVEN_HOME`、`PATH`；填写后总是用它，路径不存在时报错不回退。
2. 逐个修改五个构建选项，确认命令行里出现对应参数，Offline 和 Skip Tests 在工具栏上切换；把项目 JDK 换成另一个版本，确认 Maven 编译和应用启动都用它（[1lck/Lithe-IDEA#2203](https://github.com/1lck/Lithe-IDEA/issues/2203)）。
3. 关闭并重新打开工作区后构建选项保持；另一个工作区不受影响。

**迁移来源**：主仓库 `shared/platform-feature-matrix/features/java-maven-project-settings.json`（b582e206a）。
原条目的 canonical Run `local.json`、旧 XML 表单、配置迁移和 315 项原生回归都属于旧 Swift 宿主和旧 Maven 后端，0.0.1 没有这些实现。

## java-maven-settings-reach-jdt

**能力**：Maven 的 Settings File、Local Repository、Profiles 等选项和重新加载实时作用于 Java 语言服务（JDT LS）。

| 平台 | 实现 | 验证 | 说明 |
| --- | --- | --- | --- |
| macOS | missing | not-applicable | 0.0.1 没有后端，不导出 `languageImport.settings` |
| Windows | missing | not-applicable | 同 macOS：没有后端，不导出 `languageImport.settings` |

**说明**：Java Support 1.2.0 通过插件函数 `languageImport.settings` 向构建插件收集 JDT LS 设置，构建插件改设置后调用
`languageImport.settingsChanged`。插件函数需要后端进程来应答，而 0.0.1 是纯声明式插件，所以 Maven 的构建选项目前不会传给
JDT LS，JDT LS 按自己的默认方式导入 Maven 项目。

**迁移来源**：主仓库 `shared/platform-feature-matrix/features/java-maven-settings-reach-jdt.json`（b582e206a）。
原条目描述的 Core `build.languageImport`、MavenService 回调和 Run/Debug 准备属于旧 Swift 宿主。

## java-maven-toolbar-tooltips

**能力**：构建窗口里的 Maven 图标（IntelliJ Platform 原色 SVG，含暗色版本）、Offline 和 Skip Tests 工具栏开关、任务和选项的提示。

**证据**：`io.github.lithe-idea.maven-build/icons/`、`plugin.json` 的 `buildSystems[0].icon` 和 `options`、`NOTICE`。

**依赖的宿主能力**：构建窗口按清单显示图标、开关和提示。

**最近验证**：没有自动化测试覆盖，也没有做手工验收。

**验证方法**（macOS）：在明暗主题下打开构建窗口，逐一悬停运行、刷新、开关和选项按钮，提示与动作一致；
图标原色显示，切换主题后使用对应版本；窄窗口下提示不被截断。

**迁移来源**：主仓库 `shared/platform-feature-matrix/features/java-maven-toolbar-tooltips.json`（b582e206a）。
原条目里的 Swift 视图和回归测试名属于旧 Swift 宿主。

## maven-user-settings-editing

**能力**：没有打开项目时，在 Maven 设置页编辑用户级 `~/.m2/settings.xml` 的 `<localRepository>`，回显真实值，
确认后只改写这个元素，文件在读取后被外部修改时拒绝覆盖并保留草稿。

| 平台 | 实现 | 验证 | 说明 |
| --- | --- | --- | --- |
| macOS | missing | not-applicable | 0.0.1 没有这个设置；Local Repository 构建选项只传 `-Dmaven.repo.local`，不改 `settings.xml` |
| Windows | missing | not-applicable | 同 macOS |

**说明**：旧版 Maven 后端实现过这个功能，契约 v0 重写时没有保留。按 Lithe 的“设置必须反映工具的真实配置”规则，
重新实现时要读取并回显 `settings.xml` 的实际值，确认后才写回，并做外部修改冲突保护。

**迁移来源**：主仓库 `shared/platform-feature-matrix/features/maven-user-settings-editing.json`（b582e206a）。

## maven-project-generators

**能力**：新建项目窗口的两个条目。Maven 生成 Maven Java 项目（Java release 8、11、17、21、25，可选示例代码、GroupId、
ArtifactId）；Maven Archetype 用内置或自定义原型，由 `mvn archetype:generate` 生成项目。

**证据**：`plugin.json` 的 `projectGenerators`、`io.github.lithe-idea.maven-build/templates/java/`、
`e2e/host/desktop/internal/projectgen/maven_integration_test.go`。

**依赖的宿主能力**：`projectGenerators`（Core 计算表单和生成计划，宿主在临时目录执行后整体移动到位）。

**最近验证**：2026-11-14，macOS arm64，插件 0.0.1 发布包，宿主 `059d878d4`，`node scripts/e2e-host.mjs`：`TestMavenPluginProjectGeneratesABuildableMavenProject` PASS（0.60s）；设置 `LITHE_INTEGRATION_NETWORK=1` 和 `JAVA_HOME` 后 `TestMavenPluginArchetypeRunsRealMaven` PASS（13.07s，从 Maven Central 下载原型插件并运行真实的 `mvn archetype:generate`）。新建项目窗口的界面没有自动化覆盖。

**验证方法**（macOS）：在新建项目窗口分别用两个条目生成项目，检查表单默认值、生成的文件和打开的文件；
生成的 Maven 项目能直接 `mvn package`；取消或失败时目标目录不留下半成品。

## Linux 安装包

| 能力 | Linux ARM64 | Linux x64 | 验证入口 |
| --- | --- | --- | --- |
| 构建与安装包 | [已实现并通过原生 CI](https://github.com/Lithe-IDEA/lithe-plugin-maven/actions/runs/43619370315) | [已实现并通过原生 CI](https://github.com/Lithe-IDEA/lithe-plugin-maven/actions/runs/43619370315) | `.github/workflows/ci.yml`、[资源说明](linux-packaging.md) |
| 真实工具/语言服务器流程 | [已通过原生 CI](https://github.com/Lithe-IDEA/lithe-plugin-maven/actions/runs/43619370315) | [已通过原生 CI](https://github.com/Lithe-IDEA/lithe-plugin-maven/actions/runs/43619370315) | `scripts/package/` 和现有集成测试 |

跨架构本地包检查通过不等于原生工作流验收。既有平台验证记录保持不变。

## 原生编译设置与本地 JAR

| 能力 | macOS | Windows | 证据 |
| --- | --- | --- | --- |
| 项目默认目标、按模块字节码、继承与显式保存 | implemented / pending | implemented / pending | `scripts/compiler/` 真实引擎回归，检查 class major 52/55；原生完整 UI 验收仍 pending |
| 原生 JAR 引用与编译运行 | implemented / pending | implemented / pending | `scripts/compiler/` 编译外部 JAR 并运行引用它的程序；配置与包的只读边界见 native-settings.md |

2026-11-15：本地 macOS 自动化已通过，不将脚本通过等同于所有交互场景验收。Windows 的实机结果另随 PR 补充。

## Windows 安装包

| 能力 | Windows ARM64 | Windows x64 | 验证入口 |
| --- | --- | --- | --- |
| 构建与安装包 | 已实现（同一份包）；没有原生 CI | 已实现；[已通过原生 CI](https://github.com/Lithe-IDEA/lithe-plugin-maven/actions/runs/43728100763) | `.github/workflows/ci.yml` 的 `windows` 任务、[资源说明](windows-packaging.md) |
| 真实 Maven 流程（`mvn.cmd`） | 没有原生 CI | [已通过原生 CI](https://github.com/Lithe-IDEA/lithe-plugin-maven/actions/runs/43728100763)（runner 自带 Maven 和 3.6.3 + Java 8） | `scripts/test.mjs`（`LITHE_TEST_PLUGIN_DIR` 指向解压后的 Windows 包） |

包里只有声明资源和纯 Java 的扩展 JAR，ARM64 与 x64 的差别只在用户自己的 JDK 和 Maven，所以 ARM64 用同一份包，
但在原生 ARM64 CI 或手工验收通过前不宣称通过。

## maven-run-applications

**能力**：读取项目时把 Spring Boot 应用（`@SpringBootApplication`、`@SpringCloudApplication`、跨模块自定义 Boot `@interface` 元注解、带 `main` 的 `@EnableAutoConfiguration`，或 `spring-boot-maven-plugin`）和写明主类的
Java 应用（`exec-maven-plugin`、jar 清单）以及 Java 21+/25 `main` 入口（含无参实例 `void main()` 与紧凑源文件）列为 target/entrypoint，`run` 声明把它们变成运行配置；构建提供者
`lib/lithe-maven-provider.jar` 准备运行：编译模块及其反应堆依赖，导出运行时类路径（Spring Boot / WAR 默认纳入 `provided` 依赖）和主类，默认以构建根 `request.root` 为工作目录返回 `java -cp … 主类`。
Spring Boot 运行声明了按配置类型连接的调试：宿主带调试端口请求时，提供者加 `-agentlib:jdwp` 挂起到调试器连上，宿主再用已启用的 `java` 类型适配器（Java Support）连接（宿主流程见 [1lck/Lithe-IDEA#2113](https://github.com/1lck/Lithe-IDEA/pull/2113)）。

**证据**：`plugin.json` 的 `buildSystems[0].provider` 和 `buildSystems[0].run`、`extension/.../Applications.java`、
`extension/.../RunExport.java`、`provider/`、`scripts/test.mjs` 的运行准备检查、`test/fixtures/reactor/service/`。

**依赖的宿主能力**：`buildProviders`（独立提供者进程和 `lithe/build/prepareRun`）、运行配置解析（`fromTargets`）、
注册表连接调试（`debug.configurationType: java`，需要启用一个提供 `java` 类型 `attach` 的调试适配器）。

**最近验证**：2026-11-17，macOS arm64，插件 1.0.0 打包产物，宿主 `1ce4085e7`（合入 1lck/Lithe-IDEA#2113 的 main），`node scripts/e2e-host.mjs`：

- `TestOfficialMavenPluginDebugsASpringBootApplication` PASS（约 16s）：和 Java Support 4.0.1 发布包一起安装，在
  `ShopApplication.java` 设断点后从标题栏点调试；服务以挂起方式启动，`java` 适配器连上；`GET /hello` 停在断点，
  请求在断点处阻塞，继续后返回 `Hello, Lithe from eu-west`；在调试窗口停止后服务结束、端口释放；插件安装目录不变。

- `TestOfficialMavenPluginRunsASpringBootApplication` PASS（6.2s）：和 Java Support 4.0.1 发布包一起安装，打开真实的两模块
  Spring Boot 3.5.13 项目；运行配置下拉只列出服务 `ShopApplication`；编辑器保存的 JVM 参数、程序参数和环境变量传到了进程
  （`/hello` 返回 `Hello, Lithe from eu-west`）；控制台有 Maven 构建日志（含兄弟模块 `common`）和 Spring Boot 启动日志；
  停止后服务不再响应、端口释放；插件安装目录不变。同一轮 `TestOfficialMavenPluginBuildsAReactor` PASS。
- `node scripts/test.mjs`：提供者的运行准备、类路径、主类选择、调试参数和编译失败。

这次实测发现并在 #2102 修好了四个宿主问题：声明了提供者进程的构建系统读取写文件的依赖命令时缺少输出文件、设置页按提供者读取模型、
未命名的运行配置带上了运行 ID 前缀、准备运行时的构建输出没有显示。调试的实测又发现并在 #2113 修好了三个宿主问题：
调试适配器列表在插件变化后不刷新、运行进程 30 分钟后被准备时限结束、JDT LS 就绪后才注册命令时请求调试端口被拒。Windows 没有运行。

**验证方法**（macOS）：

1. 安装插件包，打开含 Spring Boot 模块和 `exec-maven-plugin` 模块的多模块项目，信任工作区。
2. 标题栏运行配置菜单列出每个应用；选中 Spring Boot 应用点运行，运行窗口先显示 Maven 编译输出，再显示应用输出；
   停止后应用进程退出。
3. 在运行配置编辑器里改 JVM 参数、程序参数、环境变量和入口，再次运行时生效。
4. 同时安装 Java Support，在 Spring Boot 应用里设断点后点调试：断点命中，继续后请求完成，停止后服务结束。
5. 制造编译错误后运行，看到“Maven 编译失败”的提示，没有启动残缺的应用。
6. 整个过程中插件安装目录不被写入。

## maven-task-run-configurations

**能力**：声明式 Maven 任务型运行配置类型 `Maven`（`buildSystems[0].runConfigurationTypes`，`execution: "task"`），提供 `Command line` / `命令行`（`goals`）、`Profiles`（`profiles`）、`Skip tests` / `跳过测试`（`skipTests`，`-DskipTests`）、`Work offline` / `离线工作`（`offline`，`-o`）四个表单字段；同时声明启动前任务 `Run Maven Goal` / `运行 Maven 目标`（`beforeLaunchTasks` 中的 `goal`，可添加到任意运行配置的 Before Launch 列表，在工作目录执行指定的 Maven 目标或阶段）。

| 平台 | 实现 | 验证 | 说明 |
| --- | --- | --- | --- |
| macOS | implemented | pending | 纯声明式清单与翻译，需宿主声明 `runConfigurationTypes: 0` 与 `beforeLaunchTasks: 0` |
| Windows | implemented | pending | 与 macOS 使用同一份跨平台声明和翻译，待 Windows 实机验收 |

**证据**：`io.github.lithe-idea.maven-build/plugin.json`（`requires.runConfigurationTypes`、`requires.beforeLaunchTasks`、`buildSystems[0].runConfigurationTypes`、`buildSystems[0].beforeLaunchTasks`）、`io.github.lithe-idea.maven-build/l10n/zh-Hans.json`（`runConfigurationTypes.maven.maven`、`beforeLaunchTasks.maven.goal`）、`scripts/test.mjs`。

**依赖的宿主能力**：`runConfigurationTypes: 0`、`beforeLaunchTasks: 0`。

**最近验证**：2026-11-18，macOS arm64：通过 Core `9dea6aa6eaaf830c3568b647aa7daae5018b9727` JSON Schema 校验以及 `runConfig.configurationTypes` / `runConfig.editorForm` 语义校验（`applied: "l10n/zh-Hans.json"`、`rejected: []`）；`node scripts/test.mjs` 通过清单与中文翻译结构断言；宿主 Viva 无头运行配置编辑窗口与 Before Launch 弹窗渲染通过。真实点击运行配置执行 Maven 任务的端到端宿主 UI 交互仍待手工验收。

**验证方法**（macOS）：

1. 在运行配置编辑器中新建 `Maven` 配置，分别测试输入 `goals`、`profiles`、`skipTests`、`offline` 并保存与运行，确认拼接的 `mvn` 参数准确无误。
2. 在其他运行配置（如 Spring Boot）的“启动前（Before Launch）”列表添加“运行 Maven 目标”，确认启动主进程前先执行指定 Maven 目标且中文标题与说明显示完整。
