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

## 发布步骤

```bash
# 0. 确认 main 分支 CI 全绿
# 1. 更新 CHANGELOG.md（把 Unreleased 改成版本号与日期）
# 2. 版本收口
mvn release:prepare -DreleaseVersion=0.2.0 -Dtag=v0.2.0 -DdevelopmentVersion=0.3.0-SNAPSHOT
# 或手动：修改各模块 version（parent + modules 同步）→ 提交 → 打 tag v0.2.0

# 3. push tag 触发 release.yml；或手动执行
mvn -Prelease deploy
```

`release` profile 自动完成：sources jar、javadoc jar、GPG 签名、上传 Central Portal
（autoPublish=true 时校验通过后自动发布）。

## 发布后

- 在 Central Portal 检索确认 `io.github.snowxuyu:light-query` 可见（约 10-30 分钟）。
- 更新 README 徽章与版本号。
- 为 GitHub tag 创建 Release notes（直接引用 CHANGELOG 对应段落）。

## 本地干跑（不上传）

```bash
mvn -Prelease -DskipGpg=true package
# 检查 target/ 下 jar、sources、javadoc 与 asc 签名是否齐全
```
