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

Windows 一列目前都是 `missing`：插件只发布 `maven-build-<版本>-darwin.zip`（`scripts/package.mjs`），没有 Windows 包。

## 总览

| ID | 能力 | macOS | Windows | 最近验证 |
| --- | --- | --- | --- | --- |
| `java-maven-project` | 识别 Maven 项目：构建根、模块树、内容根和任务 | implemented / pending | missing / not-applicable | 2026-11-14 宿主端到端测试通过 |
| `java-maven-profiles-dependencies` | Profiles 构建选项与依赖树 | implemented / pending | missing / not-applicable | 2026-11-14 依赖树由宿主端到端测试覆盖 |
| `java-maven-project-settings` | Maven 调用设置：Maven 程序和工作区构建选项 | partial / pending | missing / not-applicable | 2026-11-14 Maven executable 由宿主端到端测试覆盖 |
| `java-maven-settings-reach-jdt` | Maven 构建选项作用于 Java 语言服务 | missing / not-applicable | missing / not-applicable | — |
| `java-maven-toolbar-tooltips` | 构建窗口里的 Maven 图标、工具栏开关和提示 | implemented / pending | missing / not-applicable | 没有自动化覆盖 |
| `maven-user-settings-editing` | 编辑用户级 `~/.m2/settings.xml` 的本地仓库 | missing / not-applicable | missing / not-applicable | — |
| `maven-project-generators` | 新建项目：Maven 和 Maven Archetype | implemented / pending | missing / not-applicable | 2026-11-14 宿主端到端测试通过 |

前六条从主仓库迁移，最后一条是本仓库新增，用来记录随测试一起迁来的新建项目测试。

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
依赖树显示整个反应堆或单个模块的依赖（含作用域和传递依赖），反应堆内未安装的兄弟模块也能解析。

**证据**：`plugin.json` 的 `buildSystems[0].options`（`profiles`）和 `buildSystems[0].dependencies`、`extension/`、
`scripts/test.mjs`、宿主端到端测试里 `app` 模块依赖 `core` 的检查。

**依赖的宿主能力**：`buildSystems` 的构建选项、依赖命令和 `litheDependencies` 解码。

**最近验证**：2026-11-14，macOS arm64，插件 0.0.1 发布包，宿主 `059d878d4`，`node scripts/e2e-host.mjs`：`TestOfficialMavenPluginBuildsAReactor` 检查了 `app` 对反应堆兄弟模块 `core` 的依赖。Profiles 切换、作用域、传递依赖和大依赖树没有自动化覆盖。

**验证方法**（macOS）：

1. 在声明了多个 Profile 的项目里切换 Profile，模型和依赖树按所选 Profile 重新读取。
2. 依赖树的模块归属、顺序、作用域和传递依赖与 `mvn dependency:tree` 一致；依赖节点超过 6,000 个的项目能完整加载。
3. 依赖解析失败时显示 Maven 的真实错误。

**迁移来源**：主仓库 `shared/platform-feature-matrix/features/java-maven-profiles-dependencies.json`（b582e206a）。
原条目中“Java 导航使用 JDT 解析的依赖快照”属于 Java Support 插件，旧 MavenService 删除记录属于旧 Swift 宿主，都不再写在这里。

## java-maven-project-settings

**能力**：插件设置 Maven executable（全局，修改后重新读取模型）；构建选项 JDK (JAVA_HOME)、Profiles、Settings File、
Local Repository、Offline、Skip Tests，按工作区保存，加到构建、测试和读取模型的命令里。

| 平台 | 实现 | 验证 | 说明 |
| --- | --- | --- | --- |
| macOS | partial | pending | 构建选项按工作区保存，不能给单个子项目设置不同的 JDK 或选项 |
| Windows | missing | not-applicable | 没有 Windows 包 |

**证据**：`plugin.json` 的 `settings`（`mavenExecutable`）、`buildSystems[0].invocation` 和 `buildSystems[0].options`；
宿主端到端测试设置 Maven executable 后检查模型重新读取和 `compile` 都运行了设置的 `mvn`。

**依赖的宿主能力**：`settings`（插件设置页和 `reload` 生效方式）、`buildSystems` 的构建选项和可执行文件候选顺序。

**最近验证**：2026-11-14，macOS arm64，插件 0.0.1 发布包，宿主 `059d878d4`，`node scripts/e2e-host.mjs`：`TestOfficialMavenPluginBuildsAReactor` 设置 Maven executable 为一个包装脚本后，确认模型重新读取和 `compile` 都运行了它。六个构建选项、按工作区保存和 `mvnw` / `MAVEN_HOME` 的候选顺序没有自动化覆盖。

**验证方法**（macOS）：

1. Maven executable 留空时依次使用 `mvnw`、`MAVEN_HOME`、`PATH`；填写后总是用它，路径不存在时报错不回退。
2. 逐个修改六个构建选项，确认命令行里出现对应参数，Offline 和 Skip Tests 在工具栏上切换。
3. 关闭并重新打开工作区后构建选项保持；另一个工作区不受影响。

**迁移来源**：主仓库 `shared/platform-feature-matrix/features/java-maven-project-settings.json`（b582e206a）。
原条目的 canonical Run `local.json`、旧 XML 表单、配置迁移和 315 项原生回归都属于旧 Swift 宿主和旧 Maven 后端，0.0.1 没有这些实现。

## java-maven-settings-reach-jdt

**能力**：Maven 的 Settings File、Local Repository、Profiles 等选项和重新加载实时作用于 Java 语言服务（JDT LS）。

| 平台 | 实现 | 验证 | 说明 |
| --- | --- | --- | --- |
| macOS | missing | not-applicable | 0.0.1 没有后端，不导出 `languageImport.settings` |
| Windows | missing | not-applicable | 没有 Windows 包 |

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
| Windows | missing | not-applicable | 没有 Windows 包 |

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
