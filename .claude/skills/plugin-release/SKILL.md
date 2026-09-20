---
name: plugin-release
description: OneClickNavigation2.X 插件发版流程：改版本号 → CHANGELOG [Unreleased] 写变更 → copyPluginToLocalDir（自动归档 changelog + 出本地产物）→ uploadPluginToR2ByAmazonS3 发布 R2 → 公网验证 → git 提交。触发词："发布插件"、"发版"、"出个包"、"升级版本"、"copyPluginToLocalDir"、"uploadPluginToR2ByAmazonS3"。

---

# 插件发版流程（编译 → 拷贝 → 发布 → 提交）

适用场景：功能/修复已改完并编译通过，要出新版本并发布到 R2 私服（idea-plugin.oss.vaetech.uk）。
日常只编译不发版不需要本 skill（编译命令见 CLAUDE.md）。

## 流程

### 1. 前置：改动已编译通过

```bash
JAVA_HOME="/c/Program Files/JetBrains/IntelliJ IDEA 2025.2.2/jbr" \
  ./gradlew compileJava --console=plain --offline -q
```

依赖未变带 `--offline` 防 sha1 远程校验卡死；改了依赖（build.gradle.kts / buildSrc）则去掉。

### 2. CHANGELOG.md 写变更

在 `## [Unreleased]`（空的）章节下按既有格式写：一行总结 + bullet 明细。
后续 `patchChangelog` 会自动把这段归档成 `## [新版本号] - 日期`，**不要手写版本号章节**。

### 3. gradle.properties 升版本号

`pluginVersion = X.Y.Z` → patch 位 +1（fix）；大功能可升 minor。已发版本的章节不可改。

### 4. 出本地产物（自动归档 changelog）

```bash
JAVA_HOME="/c/Program Files/JetBrains/IntelliJ IDEA 2025.2.2/jbr" \
  ./gradlew copyPluginToLocalDir --console=plain --offline -q
```

- 任务链：`copyPluginToLocalDir` → `buildPlugin` + `generateLocalUpdateXml` → `patchChangelog`
- 自动完成：CHANGELOG 的 Unreleased → 新版本章节归档；`config.local.properties` 密钥注入打包资源（日志有"已注入 N 项"）；产物复制到 `local-publish/`（zip + updatePlugins.xml）

### 5. 发布到 R2

```bash
JAVA_HOME="/c/Program Files/JetBrains/IntelliJ IDEA 2025.2.2/jbr" \
  ./gradlew uploadPluginToR2ByAmazonS3 --console=plain --offline -q
```

- 凭据走环境变量（envOrProperty：环境变量优先，gradle.properties 兜底）：
  `R2_S3_REGION` / `R2_S3_ENDPOINT` / `R2_S3_ACCESS_KEY_ID` / `R2_S3_SECRET_ACCESS_KEY` / `R2_BUCKET_NAME`。
  gradle.properties 里对应项是注释占位符，真实值永不落库。
- 检查凭据是否就位（只看 key 名不回显值）：
  `env | grep -cE "^R2_S3_(REGION|ENDPOINT|ACCESS_KEY_ID|SECRET_ACCESS_KEY)="` 应为 4
  （注意正则别用 `[A-Z_]*` 匹配 `R2_S3_*`——不含数字 3，会误报缺失）
- 依赖链会重新 buildPlugin：上传的 zip 与第 4 步 local-publish 里那份有十几字节差异（zip 时间戳），正常

### 6. 公网验证

```bash
curl -s https://idea-plugin.oss.vaetech.uk/updatePlugins.xml | grep -oE 'OneClickNavigationV2\.X-[0-9.]+\.zip' | head -1
# 应输出新版本 zip 名
curl -sI https://idea-plugin.oss.vaetech.uk/OneClickNavigationV2.X-<新版本>.zip | head -3
# 应 HTTP 200 + application/zip
```

### 7. git 提交

- 显式文件清单：本次源码改动 + `CHANGELOG.md` + `gradle.properties`（+ `.claude/skills/` 等文档类改动）
- 构建产物（`local-publish/`、`buildSrc/out/`、`out/`、`.kotlin/`、`hs_err_pid*.log`、`build_compile.log`）不入库
- message 风格：`fix: ...并发布 X.Y.Z` / `chore(release): 发布 X.Y.Z 版本...`，正文列要点

## 常见坑

- **验证先行时别先跑 copyPluginToLocalDir**：它会立即把 Unreleased 归档成版本章节——若验证暴露问题改了代码，已归档章节内容写死（重跑还会重复章节）。正确顺序：`buildPlugin` 出 zip（用户 Install Plugin from Disk 或 runIde 沙箱验证，见 skill runide-robot-verify）→ 验证通过 → 再走 CHANGELOG/版本号/copyPluginToLocalDir/上传；CHANGELOG 里未做的验证项如实写「待补」，不预写「通过」
- **patchChangelog 归档失败**：`## [Unreleased]` 章节必须存在（空也可）；版本号章节手写后再跑任务不会被覆盖，会重复
- **上传报 MissingValueException**：五个 R2 环境变量有缺失，任务对所有配置 `.get()` 无默认值
- **发布后旧实例不提示更新**：确认 updatePlugins.xml 公网内容已指向新版本（第 6 步），IDE 检查更新有缓存可重启验证
