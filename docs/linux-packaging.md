# Linux 构建与验证

`node scripts/package.mjs` 生成 `-linux.zip`，同一份 Maven 扩展 JAR 与声明资源支持 ARM64 和 x64；Linux 包与 macOS 包逐字节相同。插件不附带 Maven 或 JDK，继续使用用户的 Maven 和 Java。CI 在 Linux x64 与 ARM64 上运行当前 Maven 和最低支持 Maven 3.6.3 的真实模型、依赖、构建与筛选测试。

## 资源与验证边界

所有下载、解压、编译与归档都属于本插件仓库的构建阶段。`dist/` 为输出包；`.artifacts/`、`.cache/`、`target/` 或工具自身缓存为本地构建状态，不跨工作树共享可变状态。校验过的下载以固定来源与摘要识别，提取结果不作为可信下载源。运行时只读取已安装插件，缓存、日志、索引和工作区状态归用户可写目录。Linux 不使用 macOS 代码签名或 Sparkle；本次不改变 macOS 包签名与更新基线。

原生 ARM64 和 x64 验证由 `.github/workflows/ci.yml` 执行；本机的交叉编译与归档检查不能替代原生运行结果。只有目标平台 CI 成功后才能宣称该平台通过。发布新版本后，使用实际 Release 附件的 SHA-256 更新 Store，保留所有旧版本与既有平台。
