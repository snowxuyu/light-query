# Release 手册（v0.1）

发布到 Maven Central 的完整流程。前置条件：

1. Sonatype Central Portal（central.sonatype.com）注册账号，创建 namespace
   `io.github.snowxuyu` 并完成 GitHub 验证（Central Portal 会校验 io.github.<user> 归属）。
2. 生成 GPG 密钥并上传公钥到 keyserver：
   ```bash
   gpg --gen-key
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
   ```
3. 在 GitHub 仓库配置 Encrypted Secrets：
   - `MAVEN_CENTRAL_USER` / `MAVEN_CENTRAL_TOKEN`（Central Portal 的 token）
   - `GPG_PRIVATE_KEY`（ASCII armored 私钥）/ `GPG_PASSPHRASE`

## 版本策略（固定约定）

**每合入一批新特性就发一版：小版本 +1**（如 0.4.0 → 0.5.0），不等攒批。
流程固定为：收口版本 → 提交推送 main → 打 `v0.{n}.0` tag 推送 →
`release.yml` 自动发布 Maven Central。文档修复/重构不发版，随下一次特性版带上。

## 发布步骤（每次发版照此执行）

```bash
# 0. 确认 main 分支 CI 全绿
# 1. 文档先行：DESIGN.md（契约 + 测试矩阵）与 USAGE.md 已随特性合入；
#    CHANGELOG.md 把 Unreleased 改成版本号与日期，并新建空 Unreleased 段
# 2. 版本收口（小版本 +1）：parent + light-query + starter 三个 pom 的
#    version，以及 README 两处依赖示例版本号，统一改为 0.{n}.0
# 3. 本地干跑：mvn -Prelease -Dgpg.skip=true verify
#    （178+ 测试全绿，jar/sources/javadoc 四件套齐全）
# 4. 提交 "release: v0.{n}.0" 并推送 main
# 5. 打 tag 并推送，触发 release.yml 自动发布 Maven Central：
git tag v0.{n}.0 && git push origin v0.{n}.0
```

`release` profile 自动完成：sources jar、javadoc jar、GPG 签名、上传 Central Portal
（autoPublish=true 时校验通过后自动发布）。不要手动 `mvn -Prelease deploy`，
除非 CI 不可用。

## 发布后

- 确认 GitHub Actions 的 release 运行为 success。
- 在 Central Portal 检索确认 `io.github.snowxuyu:light-query` 可见（约 10-30 分钟）。
- 更新 README 徽章与版本号。
- 为 GitHub tag 创建 Release notes（直接引用 CHANGELOG 对应段落）。

## 本地干跑（不上传）

```bash
mvn -Prelease -Dgpg.skip=true package
# 检查 target/ 下 jar、sources、javadoc 与 asc 签名是否齐全
```
