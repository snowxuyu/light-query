# AGENTS.md — light-query 项目工作指引

给在本项目目录中工作的 AI 会话（以及新加入的贡献者）的上下文与规则。

## 项目是什么

light-query：面向 Java 21 的低学习成本、类型安全轻量 ORM。
标准 Jakarta Persistence 注解 + 链式 lambda 查询，唯一运行时依赖
`jakarta.persistence-api`。仓库：`github.com/snowxuyu/light-query`。

## 铁律（违反视为 bug）

1. **`docs/DESIGN.md` 是契约**：任何行为变更必须先改该文档（含 §11 测试矩阵），
   再改代码；代码与文档不一致时以修齐一致为任务。
2. **禁止新增运行时依赖**：编译依赖只有 `jakarta.persistence-api`；
   测试依赖（JUnit/H2）不在此限。
3. **公共 API 必须有 JavaDoc**；所有框架异常消息必须"说明问题 + 指出修复方式"。
4. **SQL 只在 `sqlgen/SqlBuilder` 生成**；一切数据库语法差异必须进 `Dialect`；
   值一律 `?` 绑定，框架自身永不拼接用户输入。

## 构建与测试

```bash
# 需要 JDK 21
mvn verify        # 全量构建 + 82 个测试（T1-T10 矩阵）
mvn test          # 仅测试
```

- `SqlSnapshotTest`：SQL 生成快照，**MySQL 与 PostgreSQL 双方言断言**。
  改动 SQL 生成的 PR 必须同步更新两侧快照，并在 PR 描述里贴出前后 SQL。
- `*H2Test`：行为测试跑在 H2 内存库（建表 DDL 中标识符加了引号以保持小写，
  与方言的引号行为对齐——改 TestDb 时注意）。
- 覆盖率门禁：核心包 ≥ 85%（见 DESIGN.md §11）。

## 关键文档索引

| 文档 | 内容 |
|---|---|
| `docs/DESIGN.md` | 设计契约：API 签名、语义约定（null/空 IN/逻辑删除/全表保护）、别名规则、测试矩阵 T1-T10、Roadmap（§14） |
| `docs/RELEASE.md` | Maven Central 发布手册（Central Portal namespace：`io.github.snowxuyu`） |
| `CHANGELOG.md` | 按 Keep a Changelog 维护；用户可见变更必须更新 Unreleased 段 |

## 实施中固化的设计决策（不要无意回退）

- 条件方法签名是 `<C> eq(SFunction<C, ?> col, ...)` 泛型方法——
  `SFunction<?, ?>` 通配目标会让方法引用编译失败（javac 实验已验证）。
- 标量子查询独立命名 `eqSubQuery/neSubQuery/...`——避免与
  `eq(col, null)→IS NULL` 的重载歧义。
- `and/or(consumer)` 为 MyBatis-Plus 语义：控制分组的外连接符，
  组内默认 AND、`.or()` 切换下一个条件。
- join 结果是标准 SQL 行重复语义（一个订单匹配一行则根实体出现一次）。

## 发布与 CI

- `build.yml`：push/PR 自动 `mvn verify`。
- `release.yml`：打 `v*` tag 触发发布，前置条件见 `docs/RELEASE.md`
  （Central Portal secrets：`MAVEN_CENTRAL_USER/TOKEN`、`GPG_PRIVATE_KEY`、`GPG_PASSPHRASE`）。
