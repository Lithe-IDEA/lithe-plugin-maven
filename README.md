# Maven for Lithe

为 [Lithe](https://github.com/Lithe-IDEA/Lithe-IDEA) 提供 Maven 项目支持：识别 `pom.xml`，在 Maven 工具窗口里列出
模块、生命周期和插件目标，运行任务和测试，查看依赖树。
这是一个**社区插件**，用 Lithe 的声明式构建系统接口（插件 API 3 的 `buildSystems`）实现，
除了下面说的一个 Maven 扩展 jar，没有任何宿主代码。

> English: Maven support for Lithe through the declarative `buildSystems` plugin API — project tree, lifecycle and
> plugin goals, profiles, test runs with JUnit reports, compiler diagnostics and the dependency tree. It runs your own
> Maven with a small bundled core extension that only reads the reactor.

> **试用版**：`buildSystems` 接口还在 Lithe 的预览分支上，0.1.0 作为预发布版本提供，需要包含该接口的 Lithe 开发版。

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
| 依赖 | 按模块显示依赖树（含作用域和传递依赖），反应堆内未安装的兄弟模块也能解析 |
| 同步 | `pom.xml`、`.mvn/maven.config`、`.mvn/extensions.xml` 变化后重新读取项目 |

## 依赖的程序

插件不带 Maven，使用你项目或电脑上的 Maven，按以下顺序查找：

1. 项目里的 Maven Wrapper（`mvnw`，需要 `.mvn/wrapper/maven-wrapper.properties`）；
2. 环境变量 `MAVEN_HOME` 下的 `bin/mvn`；
3. `PATH` 里的 `mvn`。

要求 Maven 3.6.3 或更新版本、Java 8 或更新版本。没有 Maven 的话可以先安装：

```bash
brew install maven
```

## 工作方式

读取项目和依赖时，插件用 `-Dmaven.ext.class.path` 给你的 Maven 加载随包提供的
`lib/lithe-maven-extension.jar`（源码在 `extension/`），运行一个没有默认绑定的阶段 `pre-clean`：

- 扩展在 Maven 读完反应堆后，把模块、内容根、插件目标和 Profiles 写成 Lithe 的 `litheBuildModel` 文档；
  依赖则写成 `litheDependencies` 文档。文件位置由 Lithe 指定（`-Dlithe.modelOutput` / `-Dlithe.dependenciesOutput`）。
- 不会编译或打包，也不会生成 `target/`；解析依赖时只读 POM，不下载依赖 jar。
- 没有这些属性时扩展什么都不做；插件目录在运行时保持只读。

读取项目会执行 POM 中声明的 Maven 扩展和插件，首次读取还可能联网下载插件描述，所以 Lithe 会先确认你信任这个工作区。

## 安装

在 Lithe 中打开 **设置 → 插件 → 插件市场**，搜索 “Maven”，安装并按提示重启。
Lithe 已预置 Lithe Store 社区插件源；如果你之前移除了它，先在 **⚙ → 管理插件仓库…** 中重新添加
`https://github.com/Lithe-IDEA/Lithe-store`。

一个包同时支持 Apple Silicon 和 Intel Mac；Lithe for Windows 支持插件市场后会增加 Windows 包。

## 开发

```text
io.github.lithe-idea.maven-build/plugin.json   # 插件描述，目录名必须等于插件 id
io.github.lithe-idea.maven-build/icons/        # 工具窗口和工具栏图标（来源见 NOTICE）
extension/                                     # Maven 核心扩展，导出模型和依赖树
test/fixtures/reactor/                         # 三个模块的示例反应堆，供集成测试使用
scripts/package.mjs                            # 构建扩展并打包成 dist/maven-build-<版本>-darwin.zip
scripts/test.mjs                               # 用真实 Maven 运行清单里的命令并检查输出
.github/workflows/ci.yml                       # PR 时打包、用 Maven 3.6.3 和最新版测试，并用 Lithe Store 的规则检查
.github/workflows/release.yml                  # 推送 v<版本> 标签时发布 GitHub Release
```

本地需要 Node.js 22+、JDK 8+ 和 Maven（还需要 `zip`）：

```bash
node scripts/package.mjs
```

```bash
node scripts/test.mjs
```

`MVN=/path/to/mvn node scripts/test.mjs` 可以换一个 Maven 版本测试。
在 Lithe 开发版中，通过 **设置 → 插件 → ⚙ → 从磁盘安装插件…** 选择 `dist/package/io.github.lithe-idea.maven-build`
即可安装本地开发包（发行版 Lithe 不加载未签名的开发包）。

`plugin.json` 的完整格式见 Lithe 的
[插件契约](https://github.com/Lithe-IDEA/Lithe-IDEA/blob/preview/shared/contracts/plugins/v1/README.md)
和 Lithe Store 的[构建工具接入指南](https://github.com/Lithe-IDEA/Lithe-store/blob/main/docs/build-tool-integration.md)。

## 发布新版本

1. 同时修改 `plugin.json` 和 `extension/pom.xml` 的版本（例如 `0.2.0`；打包脚本会检查两者一致），合入 `main`。
2. 推送同名标签：

   ```bash
   git tag v0.2.0 && git push origin v0.2.0
   ```

3. `Release` 工作流会核对标签与版本、打包、测试并发布 Release（说明里附 SHA-256）。首个 `v0.1.0` 会自动标为预发布版本，需要包含 `buildSystems` 接口的 Lithe 开发版。
4. Lithe Store 的自动追踪会发现稳定版本并开合并请求，合并后用户即可在 Lithe 中更新。自动追踪会跳过预发布版本；`v0.1.0` 需要在 Store 中手工提交条目，并使用已发布 ZIP 的实际 SHA-256。

## 许可

[Apache-2.0](LICENSE)。图标来自 IntelliJ Platform，见 [NOTICE](NOTICE)。
