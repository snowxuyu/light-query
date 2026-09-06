# light-query 设计方案（DESIGN）

> 本文档是 LightQuery 的**完整实施方案**，代码实现必须与本文档一致。
> 任何偏离文档的实现视为 bug；任何文档变更需先改本文档再改代码。
> 版本：v0.1.0 · 目标 JDK：21 · 状态：待评审

---

## 1. 项目定位与设计原则

LightQuery 是一个**低学习成本、类型安全、可进生产环境**的轻量 ORM，开源到 GitHub。

### 1.1 一句话定位

> 会写 Java lambda 就会用的 ORM：JDK 21 + Jakarta Persistence 标准注解 + 链式查询，唯一运行时依赖是 `jakarta.persistence-api`（纯注解，无传递依赖）。

### 1.2 设计原则（按优先级排序）

| # | 原则 | 含义与落地方式 |
|---|---|---|
| P1 | **正确性** | 每个语义约定都有测试固化（§11 测试矩阵）；SQL 全部参数化，框架自身永不拼接用户值 |
| P2 | **低学习成本** | 注解用 JPA 标准（`@Table/@Column/@Id/@Transient/@Enumerated`），API 用 MyBatis-Plus 式平铺条件 + lambda；错误消息可读、指明修复方式 |
| P3 | **最小依赖** | 运行时仅依赖 `jakarta.persistence-api`（provided 级别，纯注解 jar）；不绑 MyBatis/Spring/Jackson；Spring 集成放独立 starter（v0.2） |
| P4 | **可预期** | 同一输入永远生成同一 SQL（无隐藏状态）；所有默认行为在文档中写死并给出例外出口 |
| P5 | **可诊断** | 异常携带 SQL 与参数（可关闭）；异常体系分层（§8） |

### 1.3 与现有方案对比（重写动机）

| | mybatis-dynamic-query | easy-query | **LightQuery** |
|---|---|---|---|
| API | FilterDescriptor 描述符，繁琐 | 链式 lambda，顺滑 | 链式 lambda（MyBatis-Plus 风格） |
| 学习成本 | 高 | 中 | **低** |
| 编译期要求 | 无 | APT 生成代理 | **无**（运行时 SerializedLambda） |
| 运行时依赖 | MyBatis+Jackson+commons-lang3 | 自研较多 | **仅 jakarta.persistence-api** |
| JDK | 8 | 8+ | **21**（records/sealed/模式匹配/文本块） |

### 1.4 非目标（v0.1 不做，防止范围失控）

- 分库分表、读写分离、多租户
- 一级/二级缓存（直连 JDBC，交给连接池与业务层）
- 关系映射（@OneToMany 级联加载等关联对象图管理）
- 数据变更审计/数据追踪差异更新（roadmap v0.3）

---

## 2. 技术选型与工程约束

| 项 | 决策 |
|---|---|
| JDK | 21（编译目标 21，充分利用 records、sealed、switch 模式匹配、文本块） |
| 构建 | Maven，单模块 `light-query`（core）；v0.2 增加模块 `light-query-spring-boot-starter` |
| 注解来源 | **Jakarta Persistence 3.1+**（`jakarta.persistence:*`）；仅当标准无对应物时自定义注解，目前**唯一自定义注解是 `@LogicDelete`** |
| 测试 | JUnit 5 + H2 2.x（内存库，双模式：MySQL 兼容模式与默认模式） |
| 测试策略 | 纯 SQL 生成用快照断言（不连库）；语义行为用 H2 集成测试 |
| 代码规范 | Google Java Format（CI 校验）；public API 100% JavaDoc 且 `mvn javadoc:javadoc` 零 error |
| 静态检查 | SpotBugs（CI 阻断 high priority）；JaCoCo 覆盖率报告（core 行覆盖 ≥ 85% 作为门禁） |
| CI/CD | GitHub Actions：`build.yml`（JDK21 test+coverage+spotbugs）、`release.yml`（tag 触发，Central Portal 发布） |
| 版本策略 | SemVer；1.0 前 API 可能调整（0.x 明示）；CHANGELOG 按 Keep a Changelog 维护 |
| 发布 | Maven Central（central-publishing-maven-plugin），GPG 签名走 release profile；groupId `io.github.snowxuyu`（Central Portal 标准 namespace） |

**为什么允许 jakarta.persistence-api 依赖**：它是 Jakarta EE 官方规范注解、无传递依赖、纯元数据；换取的是用户零学习的实体注解。这是 P2>P3 的显式权衡。

---

## 3. 整体架构

```
┌────────────────────────────────────────────────────────────────┐
│ API 层（com.lightquery）                        │
│   LightQuery（入口，不可变，线程安全）                          │
│   Queryable / Where / JoinOn / Updatable / Deletable           │
│   Tuple / PageResult / F（聚合表达式）                          │
├────────────────────────────────────────────────────────────────┤
│ 模型层（.query.model）—— 方言无关的中间表示（IR）               │
│   QueryModel（表+别名注册表/投影/条件树/分组/排序/分页）        │
│   TableRef（实体↔别名↔物理表）、ConditionGroup/Condition/Op    │
│   SelectExpr（列/聚合/原始表达式）、SubQueryRef                 │
├────────────────────────────────────────────────────────────────┤
│ 生成层（.sqlgen）                                              │
│   SqlBuilder：IR → 参数化 SQL（唯一 SQL 生成点）                │
│   Dialect(MySql/PostgreSql/H2)：引号、分页、别名、能力声明       │
├────────────────────────────────────────────────────────────────┤
│ 执行层（.exec）                                                │
│   ConnectionProvider(SPI)/DataSourceProvider/SingleConnProvider│
│   JdbcExecutor：执行、生成键回填、异常翻译                      │
│   RowMapper：ResultSet → 实体 / Tuple                          │
├────────────────────────────────────────────────────────────────┤
│ 基础层（.meta/.lambda/.annotation）                            │
│   EntityMeta(不可变+缓存)/ColumnMeta/LambdaUtils/@LogicDelete  │
└────────────────────────────────────────────────────────────────┘
```

**架构不变式（评审与 PR 检查依据）**：

1. API 层不出现 SQL 字符串；原始表达式只能经 `F` 进入（列名仍走方言转义）。
2. 一切 SQL 语法差异必须落在 `Dialect`；`SqlBuilder` 中不允许出现 `instanceof MySqlDialect`。
3. 一切数据库连接必须经 `ConnectionProvider`；API 层禁止出现 `DataSource/Connection` 类型泄漏（`LightQuery.of(DataSource)` 除外）。
4. `EntityMeta` 不可变；所有缓存用 `ConcurrentHashMap`，写路径只发生一次。
5. 值参数一律 `?` 绑定；字面量只允许出现在 Dialect 分页子句（框架内部可控的 long）。

**包结构**：

```
com.lightquery
├── LightQuery, PageResult, Tuple, F
├── query/     Queryable, Where, JoinOn, Updatable, Deletable
├── query/model/  QueryModel, TableRef, SelectExpr, Condition(Group), Op
├── sqlgen/    SqlBuilder, Dialect, MySqlDialect, PostgreSqlDialect, H2Dialect
├── exec/      ConnectionProvider, DataSourceConnectionProvider,
│              SingleConnectionProvider, JdbcExecutor, RowMappers
├── meta/      EntityMeta, ColumnMeta, EntityMetaCache
├── lambda/    SFunction, LambdaUtils
├── annotation/ LogicDelete        ← 唯一自定义注解
└── exception/ LightQueryException, MappingException, SqlBuildException,
               DataAccessException, UnexpectedRowsException
```

---

## 4. 实体映射规范

### 4.1 注解清单

| 注解 | 来源 | 用途 | v0.1 支持范围 |
|---|---|---|---|
| `@Table(name=...)` | jakarta.persistence | 表名 | name；缺省=类名 camel→snake |
| `@Id` | jakarta.persistence | 主键（可复合） | 必须恰有 ≥1 个，否则 MappingException |
| `@GeneratedValue(strategy=IDENTITY)` | jakarta.persistence | 自增主键 | 仅 IDENTITY；插入后回填实体；SEQUENCE 进 roadmap |
| `@Column(name=...)` | jakarta.persistence | 列名 | name；缺省=字段名 camel→snake；updatable/insertable=false 同步支持 |
| `@Transient` | jakarta.persistence | 非映射字段 | 全支持 |
| `@Enumerated(STRING/ORDINAL)` | jakarta.persistence | 枚举存储 | 全支持；**未标注时遵循 JPA 默认 ORDINAL**（文档高亮提醒） |
| `@Version` | jakarta.persistence | 乐观锁 | v0.2（注解解析预留，先不生效） |
| `@LogicDelete(normalValue=0, deletedValue=1)` | **LightQuery 自定义** | 逻辑删除 | 因 JPA 无对应标准注解，这是唯一自定义注解 |

> 原则重申：**不发明新注解**。任何新映射需求先查 Jakarta Persistence；确无对应物才自定义，且必须在本文档登记。

### 4.2 命名策略

- 缺省：camelCase → snake_case（`userName`→`user_name`，`URL`→`u_r_l`? 否——连续大写按词处理：`URL`→`url`）。
- 显式 `@Column(name)`/`@Table(name)` 永远优先。
- 不提供全局自定义命名策略接口（v0.1 明确不做，避免配置面扩大）；有此需求者用 `@Column` 逐个标注。

### 4.3 类型映射表（RowMapper 支持的 Java 类型）

| Java 类型 | JDBC 处理 |
|---|---|
| String | getString |
| boolean/Boolean | getBoolean + wasNull |
| int/long/short/byte/double/float 及包装 | 对应 getXxx + wasNull（primitive 列为 NULL 时保持 0 并在日志级文档说明） |
| BigDecimal / BigInteger | getBigDecimal / getLong→valueOf |
| byte[] | getBytes |
| java.util.Date / java.sql.Date / Time / Timestamp | 直接取（Timestamp 是 Date 子类） |
| LocalDate / LocalTime / LocalDateTime / Instant / OffsetDateTime | JDBC 4.2 直取，驱动不兼容时报 MappingException 并提示驱动版本 |
| 枚举 | @Enumerated(STRING)→name；缺省 ORDINAL→ordinal |
| 其他类型 | MappingException，指明字段与类型 |

### 4.4 实体约束（构造期校验，启动即失败）

- 无 `@Id` → MappingException（消息含类名）。
- 两个属性映射到同一列名 → MappingException。
- `@Id` 与 `@Transient` 同用 → MappingException。
- 复合主键合法（≥2 个 @Id），此时 `queryById/deleteById` 参数按声明顺序传入。
- 实体需有无参构造器（反射实例化）。

---

## 5. 核心 API 契约

以下签名是**实施契约**（方法名、参数、返回值、语义均不可随意变更）。

### 5.0 引导：`db` 实例从哪里来（先回答"入口怎么创建"）

`db` 是 `LightQuery` 类型的**实例**，由使用方在应用启动阶段创建一次（每个 DataSource 一个），之后全局复用。`LightQuery` 不可变且线程安全（§9），定位等价于 `JdbcTemplate`。

```java
// 创建：传入任意 DataSource 实现（HikariCP/Druid/容器托管），方言从 JDBC URL 自动探测
LightQuery db = LightQuery.of(dataSource);
```

各环境的推荐持有方式（README 会按此展开）：

| 环境 | 持有方式 |
|---|---|
| 普通 Java | 启动时创建，注入 DAO 构造器（或模块内 static final 常量） |
| Spring | `@Bean LightQuery lightQuery(DataSource ds) { return LightQuery.of(ds); }`，注入使用；v0.2 starter 自动装配 |
| 事务代码 | `db.inTransaction(tx -> ...)` 中拿到的 `tx` 同为 `LightQuery`，只是绑定了事务连接 |

**刻意不提供静态全局入口**：① 多数据源/多方言需要各自实例；② 静态单例不可替换、难以测试。
后文所有示例中的 `db` 均指上述方式创建的实例，不再重复创建语句。

### 5.1 入口 `LightQuery`（不可变，线程安全，建议单例）

```java
public final class LightQuery {
    public static LightQuery of(DataSource dataSource);        // 方言从 JDBC URL 自动探测
    public LightQuery dialect(Dialect dialect);                // 显式覆盖（返回新实例，Builder 语义）
    public <T> Queryable<T> queryable(Class<T> entityClass);
    public <T> Updatable<T> updatable(Class<T> entityClass);
    public <T> Deletable<T> deletable(Class<T> entityClass);

    // CRUD 捷径
    public <T> T queryById(Class<T> entity, Object... pkValues);   // 含逻辑删过滤；查不到返回 null
    public <T> T insert(T entity);                                  // 返回原实体（自增键已回填）
    public <T> List<T> insertBatch(Collection<T> entities);         // JDBC batch，返回原列表
    public <T> void update(T entity);                               // 按 PK 全量更新（含 null 列）
    public <T> void updateSelective(T entity);                      // 按 PK 更新非 null 列
    public <T> void delete(T entity);                               // 逻辑删除则转 UPDATE
    public <T> void deleteById(Class<T> entity, Object... pkValues);// 同上语义
    public long count(Class<T> entity);                             // 表级 count（带逻辑删过滤）

    // 事务：正常返回→commit；异常→rollback 并原样抛出
    public <R> R inTransaction(Function<LightQuery, R> work);
    public <R> R inTransaction(int isolationLevel, Function<LightQuery, R> work);
}
```

- 方言自动探测规则（按 JDBC URL 子协议）：`mysql/mariadb`→MySqlDialect；`postgresql`→PostgreSqlDialect；`h2`→H2Dialect；未知→抛出并提示 `dialect(...)` 显式指定。
- 事务嵌套：子 LightQuery 复用外层连接，不再 commit/rollback（文档明示不支持嵌套事务语义）。
- `UnexpectedRowsException`：`update/delete(entity)` 当影响行数 ≠ 1 时抛出（PK 不存在或并发变更）。

### 5.2 `Queryable<T>` —— 链式查询（每个请求新建，**非线程安全**）

```java
public final class Queryable<T> {
    // ── 条件（平铺恒为 AND；SFunction 一律 SFunction<?,?>，值不做静态类型检查——无 APT 的显式权衡）
    Queryable<T> eq(SFunction<?, ?> col, Object value);   // value=null → IS NULL
    Queryable<T> ne(SFunction<?, ?> col, Object value);   // value=null → IS NOT NULL
    Queryable<T> gt/ge/lt/le(SFunction<?, ?> col, Object value);
    Queryable<T> like(SFunction<?, ?> col, String contains);      // 两侧加 %；%_\ 自带转义
    Queryable<T> notLike(SFunction<?, ?> col, String contains);
    Queryable<T> startsWith(SFunction<?, ?> col, String prefix);  // 右侧 %
    Queryable<T> endsWith(SFunction<?, ?> col, String suffix);    // 左侧 %
    Queryable<T> in(SFunction<?, ?> col, Collection<?> values);   // 空集合 → 1=0
    Queryable<T> in(SFunction<?, ?> col, Object... values);
    Queryable<T> notIn(SFunction<?, ?> col, Collection<?> values);// 空集合 → 1=1
    Queryable<T> notIn(SFunction<?, ?> col, Object... values);
    Queryable<T> isNull(SFunction<?, ?> col); isNotNull(SFunction<?, ?> col);
    Queryable<T> between(SFunction<?, ?> col, Object lo, Object hi);   // 双闭区间
    Queryable<T> notBetween(SFunction<?, ?> col, Object lo, Object hi);
    // 列对列比较（join ON / 相关子查询用）
    Queryable<T> eqColumn(SFunction<?, ?> a, SFunction<?, ?> b);
    Queryable<T> neColumn/gtColumn/geColumn/ltColumn/leColumn(...);

    // ── 逻辑分组（嵌套 Where 的连接词默认 AND，组内 .or() 改变相邻连接）
    Queryable<T> and(Consumer<Where<T>> group);   // → AND ( … )
    Queryable<T> or (Consumer<Where<T>> group);   // → OR ( … )
    // 组内默认连接符为 AND，.or() 切换下一个条件的连接符（MyBatis-Plus 语义，
    // 与快照测试 SqlSnapshotTest#orGroupsAndOrChains 固化一致）

    // ── 子查询（传入 Queryable；相关子查询：lambda 类在父注册表内解析）
    Queryable<T> in(SFunction<?, ?> col, Queryable<?> sub);
    Queryable<T> notIn(SFunction<?, ?> col, Queryable<?> sub);
    Queryable<T> eqSubQuery/neSubQuery/gtSubQuery/geSubQuery/ltSubQuery/leSubQuery(SFunction<?, ?> col, Queryable<?> scalarSub);
    // 标量子查询使用独立命名：避免与 eq(col, null)→IS NULL 的重载歧义
    Queryable<T> whereExists(Queryable<?> sub); whereNotExists(Queryable<?> sub);

    // ── Join（同实体不可重复 join，违者抛 SqlBuildException——自连接 v0.2 支持）
    Queryable<T> innerJoin(Class<J> target, Consumer<JoinOn<J,T>> on);
    Queryable<T> leftJoin (Class<J> target, Consumer<JoinOn<J,T>> on);
    Queryable<T> rightJoin(Class<J> target, Consumer<JoinOn<J,T>> on);  // 方言不支持时抛异常

    // ── 排序 / 分组 / 投影 / 分页
    Queryable<T> orderByAsc(SFunction<?, ?>... cols); orderByDesc(SFunction<?, ?>... cols);
    Queryable<T> orderBy(SFunction<?, ?> col, boolean asc);
    Queryable<T> groupBy(SFunction<?, ?>... cols);
    Queryable<T> having(Consumer<Where<T>> group);
    Queryable<T> distinct();
    Queryable<T> select(SFunction<C, ?>... cols);   // 投影；join 时可为任意参与表列
    // varargs 泛型推断限制：一次调用内混用两个实体的 lambda 无法推断，分两次 .select(...) 调用即可
    Queryable<T> select(String... exprs);           // F 生成表达式；唯一字符串出口
    Queryable<T> limit(long n); offset(long n);     // 语义：LIMIT n OFFSET m
    Queryable<T> asTable(String physicalName);      // 动态表名（分表）
    Queryable<T> includeDeleted();                  // 本查询取消逻辑删过滤
    Queryable<T> forUpdate();                       // SELECT ... FOR UPDATE

    // ── 终端方法
    List<T> toList();                                // 实体映射（含 join：仅映射左实体列）
    T firstOrNull();                                 // 内部自动 LIMIT 1
    long count();                                    // 忽略 limit/offset；有 groupBy → 子查询计数
    boolean exists();                                // SELECT 1 ... LIMIT 1 判存在
    Number sum(SFunction<?,?> col); Number avg(...); Number max(...); Number min(...);
    PageResult<T> toPageResult(long pageNo, long pageSize);  // pageNo 从 1 开始
    List<Tuple> toTupleList();                       // 投影/分组查询结果
    String toSql();                                  // 调试用：返回 SQL 与参数（不打日志不执行）
}
```

### 5.3 `Where<T>` —— 条件收集器

拥有 Queryable 的全部条件方法（eq…notBetween、eqColumn 系列、`or()` 连接符、`and/or(Consumer)` 嵌套），
用于 `and(w -> ...)` / `or(w -> ...)` / `having(w -> ...)` 的 lambda 内。Queryable 的平铺条件方法即委托给内部 Where。

### 5.4 `JoinOn` —— ON 条件

```java
public final class JoinOn<A, B> {
    JoinOn<A, B> eq(SFunction<?, ?> colA, SFunction<?, ?> colB);   // 主用法：a.id = b.userId
    JoinOn<A, B> ne/gt/ge/lt/le(colA, colB);
    JoinOn<A, B> eq(SFunction<?, ?> col, Object value);            // 常量条件
    JoinOn<A, B> or();                                             // 改变下一个条件的连接符
}
```

### 5.5 聚合表达式 `F` 与 `Tuple`

```java
public final class F {
    public static String count();                    // count(*)
    public static String count(SFunction<?,?> col);
    public static String countDistinct(SFunction<?,?> col);
    public static String sum/avg/max/min(SFunction<?,?> col);
    public static String as(String expr, String alias);   // expr AS alias（Tuple 标签=alias）
}
public sealed interface Tuple {
    Object get(String exprOrAlias);                  // 键=select 时传入的表达式/别名原文
    <V> V get(String exprOrAlias, Class<V> type);
    Object get(int index); List<String> labels(); int size();
}
```

分组查询标准用法：

```java
List<Tuple> rows = db.queryable(Order.class)
    .select(Order::getStatus, F.count().as("cnt"), F.sum(Order::getAmount).as("total"))
    .gt(Order::getAmount, 100)
    .groupBy(Order::getStatus)
    .having(w -> w.gt(F.count(), 2))
    .orderByDesc("cnt")
    .toTupleList();
```

- `orderBy(String expr)` 重载用于按聚合排序（列名仍走转义与归属解析）。
- 标量聚合终端（`sum/avg/max/min`）返回 `Number`：`sum/avg` 为 BigDecimal（无行时 sum=0、avg=null），`max/min` 为驱动原生数值对象（无行时 null）。语义固定并测试固化（QueryH2Test#aggregateTerminals）。

### 5.6 `PageResult<T>`

```java
public record PageResult<T>(List<T> rows, long total, long pageNo, long pageSize) {
    public long pages();                      // ceil(total/pageSize)，pageSize=0 时为 0
    public static <T> PageResult<T> of(...);
}
```

- `toPageResult(p, s)`：先发 count（忽略排序/limit/offset，带 groupBy 时子查询计数），再发分页查询。两次查询**不在同一事务**（无事务时各自独立连接），文档明示。
- `pageNo<1` 或 `pageSize<1` → IllegalArgumentException。

### 5.7 `Updatable<T>` / `Deletable<T>`

```java
public final class Updatable<T> {
    Updatable<T> set(SFunction<?,?> col, Object value);
    Updatable<T> setNull(SFunction<?,?> col);
    Updatable<T> setIncrement(SFunction<?,?> col, long delta);   // col = col + ?（数值列）
    // 条件方法与 Where 相同（eq…、and/or 分组、in 等）
    int execute();                       // 返回影响行数；自动追加逻辑删过滤
}
public final class Deletable<T> {
    Deletable<T> physical();             // 有 @LogicDelete 时强制 DELETE
    // 条件方法与 Where 相同
    int execute();                       // 逻辑删除实体 → UPDATE deleted=?；无任何条件 → 抛异常（防全表）
}
```

- `Updatable.set` 不允许设置主键列（SqlBuildException）。
- `Updatable`/`Deletable` **无 where 条件直接 execute 抛 `SqlBuildException`**（UPDATE/DELETE 全表必须显式 `.allowFullTable()` 才放行——提供该逃生门并打文档警告）。

### 5.8 事务示例（契约行为）

```java
Long orderId = db.inTransaction(tx -> {
    tx.insert(order);                                   // 自增键回填
    tx.updatable(Account.class)
      .setIncrement(Account::getBalance, -order.getAmount())
      .eq(Account::getId, accountId)
      .execute();                                       // 影响行数≠1 → UnexpectedRowsException → 回滚
    return order.getId();
});                                                     // 异常：rollback 后原样抛出
```

---

## 6. SQL 生成规则（SqlBuilder + Dialect）

### 6.1 别名规则（join 与子查询的正确性根基）

| 场景 | 别名 |
|---|---|
| 纯单表（无 join） | **不使用别名**，SQL 保持 `SELECT * FROM t_user WHERE ...` 干净形态 |
| join 参与表 | 根表 `t0`，按 join 加入顺序 `t1, t2, ...`；条件列渲染为 `t1.user_id` |
| 子查询（深度 d≥1） | 内部别名 `s{d}_0, s{d}_1, ...`，与外层 `t{n}` 不冲突；相关子查询引用外层列直接用外层别名 |

- **列归属解析**：lambda → `LambdaUtils.getImplClass()` → 注册表（类→TableRef）→ `别名.列名`。lambda 类不在注册表 → SqlBuildException（消息列出当前可用实体，指明“忘记 join”）。
- **同实体重复 join**（自连接）：v0.1 抛 SqlBuildException（类→别名映射无法消歧）；v0.2 以 `QueryTable` 显式别名方案支持。

### 6.2 通用规则

- 标识符转义：MySQL `` ` ``、PG/H2 `"`；转义字符双写。聚合表达式内层标识符同样转义。
- `SELECT` 列表：无投影→`*`（join 模式→`t0.*`）；有投影→按声明顺序。
- 逻辑删除过滤注入点：WHERE 树**最外层 AND 组追加** `deleted = 0`（`includeDeleted()`/物理删时跳过），保证与用户条件的组合恒为 AND 语义。
- `count()`：无 groupBy → `SELECT COUNT(*) ...`；有 groupBy → `SELECT COUNT(*) FROM (去排序分页的内部查询) t_sub`。
- `firstOrNull`/`exists()`：`LIMIT 1`；`exists()` 投影固定 `SELECT 1`。
- `IN` 空 → `1=0`，`NOT IN` 空 → `1=1`（测试固化）。
- `like` 值转义：`\`→`\\`、`%`→`\%`、`_`→`\_` 后两侧加 `%`（MySQL 默认转义符 `\`；PG 文档提示需 `LIKE ... ESCAPE '\'`，由 Dialect 输出 ESCAPE 子句）。

### 6.3 方言差异表（Dialect 接口方法）

| 能力 | MySQL | PostgreSQL | H2 |
|---|---|---|---|
| 引号 | `` ` `` | `"` | `"` |
| 分页 | `LIMIT n OFFSET m` | 同左 | 同左 |
| rightJoin | ✅ | ✅ | ✅ |
| like ESCAPE 子句 | 依赖默认 `\`，不输出 | 显式输出 `ESCAPE '\'` | 显式输出 |
| upsert / 序列等 | roadmap | roadmap | roadmap |

### 6.4 生成 SQL 样例（快照测试基准）

```sql
-- join + 子查询 + 分组
SELECT `t0`.`id`, `t0`.`user_name`, `t0`.`amount`
FROM `t_user` `t0`
LEFT JOIN `t_order` `t1` ON `t0`.`id` = `t1`.`user_id`
WHERE `t0`.`status` = ? AND `t1`.`amount` > ? AND `t0`.`deleted` = 0
  AND `t0`.`id` IN (SELECT `s1_0`.`user_id` FROM `t_order` `s1_0` GROUP BY `s1_0`.`user_id` HAVING COUNT(*) > ?)
ORDER BY `t0`.`create_time` DESC
LIMIT 20 OFFSET 0
```

---

## 7. 执行与行映射

- `JdbcExecutor`：每操作从 `ConnectionProvider` 取连接，finally 归还；异常统一翻译为 `DataAccessException`（携带 SQL、参数、SQLException 链）。
- 生成键回填：`insert` 用 `RETURN_GENERATED_KEYS`，仅回填单个 `@GeneratedValue(IDENTITY)` 主键（复合主键 + 自增的组合非法）。
- `insertBatch`：JDBC addBatch/executeBatch，按首行列集合渲染单条 SQL（要求同构，文档明示 null 差异列也占位）。
- 映射：按 `ResultSetMetaData#getColumnLabel` → EntityMeta 列名（精确→大小写不敏感→snake↔camel 回退）；无匹配列忽略（join 模式下非左实体列自然丢弃）。
- `fetchSize`：默认不设；`Queryable.fetchSize(int)` 提供大结果集逃生门（MySQL 需 `Integer.MIN_VALUE` 流式，文档说明）。

---

## 8. 异常体系（全部 unchecked）

```
LightQueryException                        (抽象根)
├── MappingException                       实体元数据/lambda 解析/行映射/类型转换
├── SqlBuildException                      API 误用：未 join 引用列、空条件 execute、重复 join、方言不支持
├── DataAccessException                    JDBC 执行失败（携带 SQL+参数+原因链）
└── UnexpectedRowsException                期望 1 行实际 0/N 行（byId 系列、update/delete 实体）
```

消息规范：一句话说明问题 + 如何修复（如 “Property `orders` referenced by lambda is not part of this query. Available: t_user, t_order. Did you forget to join?”）。

---

## 9. 线程安全与生命周期

| 对象 | 线程安全 | 生命周期 |
|---|---|---|
| LightQuery / Dialect / EntityMeta | ✅ 不可变 | JVM 级单例共享 |
| Queryable/Where/Updatable/Deletable/JoinOn | ❌ | 一次请求内构建→终端方法消费，禁止复用与跨线程传递 |
| Terminal 方法调用后 | Queryable 置为 consumed，再调用抛 IllegalStateException | 防止误用 |

---

## 10. 性能设计

1. 元数据/lambda 解析全局缓存，热路径零反射查找（ConcurrentHashMap get）。
2. SQL 渲染纯内存字符串构建，无正则热路径。
3. 不做语句缓存（连接池/驱动层已做），不做结果缓存（原则 P4：可预期）。
4. insertBatch 走 JDBC batch；fetchSize 可调（§7）。
5. 分页 count 与 rows 两查（可预期性优先）；游标/seek 分页 roadmap v0.3。

---

## 11. 测试矩阵（发布门禁，全部自动化）

| # | 测试类 | 覆盖点 |
|---|---|---|
| T1 | EntityMetaTest | 命名转换（含连续大写）、注解组合、枚举两种策略、无主键/重复列/非法组合报错、复合主键 |
| T2 | LambdaUtilsTest | getXxx/isXxx、非 getter 报错、缓存生效 |
| T3 | SqlSnapshotMySqlTest / SqlSnapshotPostgreSqlTest | §5 全部方法生成 SQL 逐条快照：全操作符、or() 链、嵌套分组、null 语义、空 IN、join 别名与 ON、相关/非相关子查询、标量子查询、groupBy+having、count 子查询、分页子句、forUpdate、distinct、投影+as、like ESCAPE、动态表名 |
| T4 | CrudH2Test | insert/回填/批量/selective、queryById、update/delete 实体（行数≠1 异常）、count |
| T5 | QueryH2Test | 操作符行为验证（不只是 SQL 形状）、分页 totals、Tuple 投影、标量聚合边界（空表 max=null、sum=0） |
| T6 | JoinSubqueryH2Test | join 语义（inner/left 差异、行重复为标准 SQL 行为）、列对列条件、in 子查询、标量子查询、相关 exists |
| T7 | LogicDeleteH2Test | 自动过滤、delete→update、includeDeleted、physical、Deletable 转 UPDATE |
| T8 | TransactionH2Test | commit、rollback（异常原样抛出）、isolation 参数、嵌套复用连接 |
| T9 | SafetyTest | 空 IN、无条件 execute 拒绝、allowFullTable、SQL 注入向量尝试（值含引号/分册/注释）全参数化 |
| T10 | ConcurrencySmokeTest | 多线程共享 LightQuery 并发构建查询 |

覆盖率门禁：JaCoCo core 指令覆盖 ≥ 85%，`sqlgen`/`meta` 包 ≥ 90%。

---

## 12. 开源工程化（GitHub 就绪清单）

### 12.1 仓库目录

```
light-query/
├── docs/ DESIGN.md(本文) API.md(按特性 cookbook+生成SQL) ROADMAP.md RELEASE.md(发布手册)
├── src/ main|test
├── .github/workflows/ build.yml release.yml
├── .github/ ISSUE_TEMPLATE bug_report.md feature_request.md, pull_request_template.md, FUNDING.yml(可选)
├── README.md(中文为主+English Quick Start) README_EN.md(链接式简介)
├── CONTRIBUTING.md  CODE_OF_CONDUCT.md  SECURITY.md
├── LICENSE  CHANGELOG.md  .editorconfig  .gitignore  pom.xml
```

### 12.2 README 结构（低学习成本的关键载体）

1. 一句话定位 + 特性徽章（CI/Coverage/maven central/JDK21）
2. 30 秒上手：实体（JPA 注解）+ 增删改查 + 条件查询完整可复制示例
3. 与 MyBatis-Plus / easy-query 对比表（§1.3）
4. 特性导航：条件操作符表、join、子查询、聚合分页、事务、逻辑删除
5. 语义约定速查（null/空 IN/逻辑删除/全表保护）
6. 常见问题（枚举默认 ORDINAL 警告、MySQL 流式 fetchSize、方言扩展）

### 12.3 CI 流水线

- `build.yml`：push/PR → JDK21 `mvn -B verify`（含 SpotBugs、JaCoCo 检查、javadoc 校验）→ 上传覆盖率 artifact。
- `release.yml`：tag `v*` → GPG 签名 + Central Portal 发布（secrets 走 GitHub Encrypted Secrets）。
- 分支模型：`main` 保护 + PR 必须过 CI；`main` 合并自动部署 snapshot（可选）。

### 12.4 社区与合规

- LICENSE：Apache-2.0（与作者现有生态一致）；所有源文件带 license header（CI 校验）。
- CONTRIBUTING：本地构建命令、测试要求（新特性必须带 T3 快照 + H2 行为测试）、DESIGN.md 变更先行原则。
- SECURITY.md：漏洞报告邮箱与响应承诺。
- 首个版本发布前完成：Maven Central namespace 校验、Javadoc 中文乱码检查（`-Dfile.encoding=UTF-8`）。

---

## 13. 实施里程碑与验收标准

| 里程碑 | 内容 | 验收 |
|---|---|---|
| **M1 基础层** | 注解(替换为 jakarta)/lambda/EntityMeta/Dialect 三件/SqlBuilder 单表/ConnectionProvider/JdbcExecutor/RowMapper | T1 T2 + T3 单表部分 + T4 全绿 |
| **M2 API 层单表** | LightQuery/Where/Queryable 全条件/排序/limit/Tuple/CRUD/Updatable/Deletable/逻辑删除 | T3 T5 T7 T9 全绿 |
| **M3 join/子查询/聚合/分页** | TableRef 别名注册/JoinOn/子查询(相关)/F/Tuple/PageResult | T3 join 部分 + T6 + 分页用例全绿 |
| **M4 事务与健壮性** | inTransaction/异常体系/consumed 防复用/fetchSize | T8 T10 全绿 |
| **M5 开源发布** | CI/文档全套/发布 profile/README | CI 全绿 + 文档审阅 + 本地 Central 干跑 |

当前进度：M1 已完成约 50%（注解层需按本文档替换为 jakarta 并重跑）。

---

## 14. Roadmap

| 版本 | 内容 |
|---|---|
| v0.2 | `@Version` 乐观锁、`SEQUENCE` 主键、自连接（QueryTable）、spring-boot-starter（ConnectionProvider 对接 Spring 事务）、字段自动填充监听器 SPI |
| v0.3 | Oracle/SQLServer/达梦方言、seek 分页（逻辑分页）、VO/record 投影 select、审计拦截器 SPI、update join / delete join |
| v1.0 | API 冻结、长期兼容承诺、性能基准报告（JMH）、多驱动兼容矩阵 |
