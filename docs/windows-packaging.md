# Windows 构建与验证

`node scripts/package.mjs` 同时生成 `-windows.zip`，与 `-darwin.zip`、`-linux.zip` 逐字节相同：包里只有声明资源和纯 Java 的
Maven 扩展 JAR，没有原生代码，一份包同时用于 Windows ARM64 和 x64。插件不附带 Maven 或 JDK，继续使用用户的 Maven 和 Java。

## Windows 上怎么找 Maven

`plugin.json` 已经按平台声明了候选：

1. 设置里的 Maven executable；
2. 项目里的 `mvnw.cmd`（要求存在 `.mvn/wrapper/maven-wrapper.properties`）；
3. `MAVEN_HOME\bin\mvn.cmd`；
4. `PATH` 上的 `mvn`。宿主按 `PATHEXT` 查找，所以会找到 `mvn.cmd`。

新建 Maven Archetype 项目时运行的 `mvn archetype:generate` 也按第 4 条查找。

## 资源与验证边界

打包需要 POSIX `zip`，只在 macOS 或 Linux 上运行；Windows 包在 Linux CI 上生成。`scripts/test.mjs` 可以在 Windows 上运行：
`scripts/process.mjs` 通过 `cmd.exe /d /v:off /s /c` 启动命令（Node 不能不经 shell 启动 `.cmd`），超时或中断时用
`taskkill /T /F` 结束整个进程树。会被 `cmd.exe` 展开或拆开的参数（`"`、`%`、`!`、换行）直接拒绝，不做转义。
`scripts/test-process.mjs` 的进程组回归测试只在 POSIX 上运行。

`.github/workflows/ci.yml` 的 `windows` 任务下载 Linux 打包任务生成的 `-windows.zip`，解压到带空格的目录（`Lithe Plugins`），
再用 `LITHE_TEST_PLUGIN_DIR` 指向解压结果，分别用 runner 自带的 Maven（Java 17）和最低支持的 Maven 3.6.3（Java 8，校验 SHA-512）
通过 `mvn.cmd` 运行声明的模型、依赖、筛选测试和空操作命令。

Maven 在 Windows 上只规范化子模块路径（`getCanonicalFile`），不规范化执行目录。执行目录带 8.3 短名（如 runner 的临时目录
`C:\Users\RUNNER~1\...`）时，扩展导出模型和依赖时比较规范化路径，仍能匹配父项目和模块；但 Maven 自己按执行目录解析
`-pl core`，会报找不到所选项目。Lithe 使用打开文件夹时的长路径，所以 `scripts/test.mjs` 用 `realpathSync.native` 把临时目录
展开成长路径再运行。

只有 Windows x64 有原生 CI；ARM64 用同一份包，在原生 ARM64 验证通过前不宣称通过。发布新版本后，使用实际 Release 附件的
SHA-256 更新 Store，保留所有旧版本与既有平台。
