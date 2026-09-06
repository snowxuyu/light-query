# light-query 设计方案（DESIGN）

> 本文档是 LightQuery 的**完整实施方案**，代码实现必须与本文档一致。
> 任何偏离文档的实现视为 bug；任何文档变更需先改本文档再改代码。
> 版本：v0.2.0-SNAPSHOT · 目标 JDK：21 · 状态：评审中

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
| 构建 | Maven 双模块：`light-query`（core）+ `light-query-spring-boot-starter`（v0.2 起，可选依赖 Spring Boot） |
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
│   LightQuery（静态门面 + 全局注册表）                          │
│   LightQuerySession（绑定数据源，不可变，线程安全）             │
│   Queryable / Where / JoinOn / Updatable / Deletable           │
│   Tuple / PageResult / Aggregations（聚合表达式）                │
├────────────────────────────────────────────────────────────────┤
│ 模型层（.query.model）—— 方言无关的中间表示（IR）               │
│   QueryModel（表+别名注册表/投影/条件树/分组/排序/分页）        │
│   TableRef（实体↔别名↔物理表）、ConditionGroup/Condition/Operator│
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

1. API 层不出现 SQL 字符串；原始表达式只能经 `Aggregations` 进入（列名仍走方言转义）。
2. 一切 SQL 语法差异必须落在 `Dialect`；`SqlBuilder` 中不允许出现 `instanceof MySqlDialect`。
3. 一切数据库连接必须经 `ConnectionProvider`；API 层禁止出现 `DataSource/Connection` 类型泄漏（`LightQuery` 门面的 `primary/datasource/of` 注册入参除外）。
4. `EntityMeta` 不可变；所有缓存用 `ConcurrentHashMap`，写路径只发生一次。
5. 值参数一律 `?` 绑定；字面量只允许出现在 Dialect 分页子句（框架内部可控的 long）。

**包结构**：

```
com.lightquery
├── LightQuery（静态门面）, LightQuerySession, PageResult, Tuple, Aggregations
├── query/     Queryable, Where, JoinOn, Updatable, Deletable
├── query/model/  QueryModel, TableRef, SelectExpr, Condition(Group), Operator
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
| `@GeneratedValue(strategy=IDENTITY)` | jakarta.persistence | 自增主键 | IDENTITY：插入后回填实体；SEQUENCE：见下一行（v0.2 起） |
| `@GeneratedValue(strategy=SEQUENCE)` + `@SequenceGenerator` | jakarta.persistence | 序列主键 | v0.2 起：必须配套 `@SequenceGenerator(sequenceName=...)`；插入前取 `nextval` 回填实体，id 随 INSERT 写入；仅 PG/H2 方言支持（MySQL 抛 SqlBuildException）；`allocationSize` 仅登记不参与取值（逐次取值，不支持 pooled 预分配） |
| `@Column(name=...)` | jakarta.persistence | 列名 | name；缺省=字段名 camel→snake；updatable/insertable=false 同步支持 |
| `@Transient` | jakarta.persistence | 非映射字段 | 全支持 |
| `@Enumerated(STRING/ORDINAL)` | jakarta.persistence | 枚举存储 | 全支持；**未标注时遵循 JPA 默认 ORDINAL**（文档高亮提醒） |
| `@Version` | jakarta.persistence | 乐观锁 | v0.2 起生效：仅 `int/Integer/long/Long`（其他类型 MappingException）；每实体至多一个；作用于 `update/updateSelective/delete(entity)`（实体级操作），fluent `Updatable/Deletable` 与 `deleteById` 不自动参与（可手动 eq 版本列） |
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

### 5.0 引导：静态门面 `LightQuery` 与会话 `LightQuerySession`（先回答"入口怎么用"）

`LightQuery` 是**静态门面**：业务代码直接静态调用，无需创建/持有实例。
`LightQuerySession` 是绑定一个数据源（或一个事务连接）的**不可变会话**（线程安全，§9），
门面的每次调用都委托给注册的会话；`inTransaction` 的 lambda 拿到的也是会话。

```java
// 启动时注册一次：主数据源 = 默认目标（方言从 JDBC URL 自动探测）
LightQuery.primary(dataSource);

// 单数据源：任意位置直接用，无需任何指定
List<User> users = LightQuery.queryable(User.class).eq(User::getStatus, Status.ACTIVE).toList();
```

各环境的推荐接入方式（README 会按此展开）：

| 环境 | 接入方式 |
|---|---|
| 普通 Java | 启动时 `LightQuery.primary(ds)`，之后全局静态调用 |
| Spring | 启动时执行 `LightQuery.primary(ds)`（v0.2 starter 自动装配）；或 `@Bean LightQuerySession` 注入使用 |
| 多数据源 | 启动时 `LightQuery.datasource("order", dsOrder)` 注册；调用点 `LightQuery.datasource("order")` 指定 |
| 事务代码 | `LightQuery.inTransaction(tx -> ...)` 中拿到的 `tx` 是绑定事务连接的 `LightQuerySession` |

**静态全局入口的取舍（v0.1 起，应用属主决策）**：门面只是薄委托——全部能力都在不可变的
`LightQuerySession` 上，DI 场景用 `LightQuery.of(ds)` 创建独立会话即可绕开门面（可替换、可测试）；
`LightQuery.reset()` 供测试与容器重启清空注册表。
后文所有示例统一用静态门面 `LightQuery.xxx(...)` 书写，不再重复注册语句。

#### 多数据源：默认主库，`datasource(...)` 显式指定

数据源注册表在门面内**全局**维护，主数据源是一切未指定数据源调用的默认目标；
指定永远显式，框架不做任何自动路由。

```java
// 启动时注册（或直接用下面的调用点内联形式，效果等价）
LightQuery.primary(dsMain);                          // 主数据源 = 默认目标
LightQuery.datasource("order", dsOrder);             // 方言从 JDBC URL 自动探测
LightQuery.datasource("report", dsReport, dialect);  // 可显式指定方言（重载）

// 单数据源：只有主数据源，任何调用无需指定
LightQuery.queryable(User.class)...                  // → 主数据源

// 多数据源：调用点指定数据源（返回绑定该库的 LightQuerySession，能力完整）
LightQuery.datasource("order", dsOrder).queryable(Order.class)...  // 内联注册 + 使用
LightQuery.datasource("order").queryable(Order.class)...           // 按名解析
```

契约（测试固化，见 T11）：

- 未指定数据源的一切 CRUD/查询/事务都走主数据源；未注册主库时抛 `LightQueryException` 并给出注册方式。
- `datasource(name, ds)` 为 get-or-register 语义：同名同库复用并返回**同一**缓存会话，
  同名不同库抛 IllegalStateException；返回的会话不可变、线程安全、能力完整。
- 未知名称抛 `SqlBuildException`，消息列出全部可用名称并说明主数据源无需指定。
- 方言在首次注册时探测（每个数据源建一次连接）；探测失败可换用显式方言重载。
- 主库重复注册：同库幂等返回；不同库抛 IllegalStateException。`reset()` 清空全部注册（测试/重启逃生门）。
- 事务：`datasource(name)` 得到的会话可正常 `inTransaction`（事务绑定该数据源的连接）；
  主库事务内通过 `LightQuery.datasource(name)` 发出的语句运行在**当前事务之外**
  （各语句独立连接，立即提交）——跨库写入无分布式事务语义，文档明示。
- 边界不变（§1.4）：不做读写分离/分库分表/多租户等自动路由。

### 5.1 门面 `LightQuery`（静态）与会话 `LightQuerySession`（不可变，线程安全）

```java
public final class LightQuery {                                  // 静态门面（全局注册表）
    // ── 注册（启动时一次；get-or-register，重复注册同库幂等）
    public static LightQuerySession primary(DataSource ds);                  // 主数据源 = 默认目标
    public static LightQuerySession primary(DataSource ds, Dialect dialect);
    public static LightQuerySession datasource(String name, DataSource ds);  // 注册/复用并返回会话
    public static LightQuerySession datasource(String name, DataSource ds, Dialect dialect);
    public static LightQuerySession datasource(String name);                 // 按名解析
    public static void reset();                                              // 清空注册（测试/重启逃生门）
    // ── 独立实例（DI 场景，不进注册表）
    public static LightQuerySession of(DataSource ds);                       // 方言自动探测
    public static LightQuerySession of(DataSource ds, Dialect dialect);
    // ── 主数据源快捷方式（逐行委托给主库会话，签名与会话实例方法一致）
    public static <T> Queryable<T> queryable(Class<T> entityClass);
    public static <T> Updatable<T> updatable(Class<T> entityClass);
    public static <T> Deletable<T> deletable(Class<T> entityClass);
    public static <T> T queryById(Class<T> entity, Object... pkValues);   // 含逻辑删过滤；查不到返回 null
    public static <T> T insert(T entity);                                  // 返回原实体（自增键已回填）
    public static <T> List<T> insertBatch(Collection<T> entities);         // JDBC batch，返回原列表
    public static <T> void update(T entity);                               // 按 PK 全量更新（含 null 列）
    public static <T> void updateSelective(T entity);                      // 按 PK 更新非 null 列
    public static <T> void delete(T entity);                               // 逻辑删除则转 UPDATE
    public static int deleteById(Class<?> entity, Object... pkValues);     // 幂等：0 行返回 0 不抛
    public static long count(Class<?> entity);                             // 表级 count（带逻辑删过滤）
    public static <R> R inTransaction(Function<LightQuerySession, R> work); // 异常 rollback 并原样抛出
}

public final class LightQuerySession implements QueryExecutor {     // 绑定一个数据源，不可变
    public LightQuerySession(DataSource ds);                         // 方言自动探测
    public LightQuerySession(DataSource ds, Dialect dialect);
    public LightQuerySession(ConnectionProvider connections, Dialect dialect); // 集成者入口（starter 用）
    public LightQuerySession dialect(Dialect dialect);               // 显式覆盖（返回新会话）
    // queryable / updatable / deletable / CRUD 捷径 / inTransaction 与门面静态方法一一对应（实例版）
}
```

- 方言自动探测规则（按 JDBC URL 子协议）：`mysql/mariadb`→MySqlDialect；`postgresql`→PostgreSqlDialect；`h2`→H2Dialect；未知→抛出并提示显式 `Dialect` 重载。
- 多数据源：见 §5.0"多数据源"——主数据源默认，`datasource(...)` 显式指定；get-or-register，同名不同库冲突抛 IllegalStateException。
- 事务嵌套：子 LightQuerySession 复用外层连接，不再 commit/rollback（文档明示不支持嵌套事务语义）。
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

    // ── Join（同实体不可重复 join，违者抛 SqlBuildException；自连接走 QueryTable，见 §5.9）
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
    Queryable<T> select(String... exprs);           // Aggregations 生成表达式；唯一字符串出口
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

### 5.5 聚合表达式 `Aggregations` 与 `Tuple`

```java
public final class Aggregations {
    public static Aggregate count();                 // count(*)
    public static Aggregate count(SFunction<?,?> col);
    public static Aggregate countDistinct(SFunction<?,?> col);
    public static Aggregate sum/avg/max/min(SFunction<?,?> col);
}
public record Aggregate(String function, SFunction<?,?> property, boolean distinct, String alias) {
    public Aggregate as(String alias);               // 标签：ORDER BY 别名与 Tuple 键
}
public final class Tuple {
    Tuple(List<String> labels, List<Object> values); // 标签重复时首个生效（index 内部构建）
    Object get(String exprOrAlias);                  // 键=select 时传入的表达式/别名原文
    <V> V get(String exprOrAlias, Class<V> type);
    Object get(int index); List<String> labels(); int size();
}
```

分组查询标准用法：

```java
List<Tuple> rows = LightQuery.queryable(Order.class)
    .select(Order::getStatus, Aggregations.count().as("cnt"), Aggregations.sum(Order::getAmount).as("total"))
    .gt(Order::getAmount, 100)
    .groupBy(Order::getStatus)
    .having(w -> w.gt(Aggregations.count(), 2))
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
Long orderId = LightQuery.inTransaction(tx -> {
    tx.insert(order);                                   // 自增键回填
    tx.updatable(Account.class)
      .setIncrement(Account::getBalance, -order.getAmount())
      .eq(Account::getId, accountId)
      .execute();                                       // 影响行数≠1 → UnexpectedRowsException → 回滚
    return order.getId();
});                                                     // 异常：rollback 后原样抛出
```

### 5.9 自连接：`QueryTable` / `TableColumn`

同一实体多次出现在一个查询里时，lambda 的声明类无法消歧，必须用 `QueryTable`
显式命名每次出现，并用 `TableColumn` 引用具体哪一 occurrence 的列：

```java
QueryTable<Employee> manager = QueryTable.of(Employee.class, "mgr");
QueryTable<Employee> staff   = QueryTable.of(Employee.class, "staff");

List<Tuple> rows = LightQuery.queryable(staff)
    .leftJoin(manager, on -> on.eqColumn(staff.col(Employee::getManagerId), manager.col(Employee::getId)))
    .eq(manager.col(Employee::getStatus), Status.ACTIVE)          // 条件可引用任意 occurrence
    .select(staff.col(Employee::getName), manager.col(Employee::getName).as("manager_name"))
    .toTupleList();
```

契约：

- `QueryTable.of(entityType, alias)`：alias 必填且查询内唯一；`queryable(QueryTable)` 以其作为根表
  （显式别名），`innerJoin/leftJoin/rightJoin(QueryTable, on)` 以其作为 join 目标——同一实体可 join 多次。
- `table.col(SFunction)` 产出 `TableColumn`；`TableColumn.as(alias)` 用于投影标签。
  条件/排序/分组/投影提供 `TableColumn` 重载（eq…、in、between、isNull、like 系列、eqColumn 系列、
  select/groupBy/orderBy）。
- 显式别名的列渲染为 `alias.column`；引用未注册 occurrence 的 `TableColumn` → SqlBuildException。
- 当查询中同一实体出现多次时，**普通 lambda 条件引用该实体会抛 SqlBuildException**（提示改用
  `TableColumn`）；未重复的实体仍可继续用 lambda。同一 occurrence 上 `TableColumn` 与 lambda 混用合法。
- 逻辑删过滤仍只追加在根表（与 join 语义一致）；被 join occurrence 的已删行不自动过滤。

### 5.10 字段自动填充 `FillListener`（SPI）

```java
public interface FillListener {
    default void onInsert(Object entity) {}
    default void onUpdate(Object entity) {}
}
```

- `LightQuery.setFillListener(listener)` 全局注册（`clearFillListener()` 取消；`reset()` 一并清空）。
- 生效范围：`insert / insertBatch / update / updateSelective`（实体级操作）——插入前回调 `onInsert`，
  更新前回调 `onUpdate`（每实体一次，构建 SQL 之前）；监听器直接调用实体 setter 填充字段。
- fluent `Updatable/Deletable` 与 `delete/deleteById` 不回调；监听器抛出的异常原样传播（并使当前
  操作失败，事务语义不变）。

### 5.11 spring-boot-starter（`light-query-spring-boot-starter`）

```xml
<dependency>
    <groupId>io.github.snowxuyu</groupId>
    <artifactId>light-query-spring-boot-starter</artifactId>
    <version>…</version>
</dependency>
```

- 自动装配（Boot 3.x，`AutoConfiguration.imports`）：容器存在唯一候选 `DataSource` 时，
  注册 `LightQuerySession` Bean 并把同一数据源注册为门面主库（`LightQuery.primary`）——
  门面静态调用与注入的会话等价；用户自定义 `LightQuerySession` Bean 时跳过。
- **Spring 事务对接**：starter 提供 `SpringConnectionProvider`（`ConnectionProvider` SPI 实现）——
  Spring 事务活跃时经 `DataSourceUtils` 取绑定连接（参与 Spring 的 commit/rollback），
  无事务时逐操作取池化连接；`ConnectionProvider` 新增 default 方法 `inManagedTransaction()`，
  为 true 时会话的 `inTransaction` 不再自行 commit/rollback（交由外部事务管理器）。
- 多 `DataSource` 候选时不自动装配（`@ConditionalOnSingleCandidate`），按 §5.0 手动注册。

---

## 6. SQL 生成规则（SqlBuilder + Dialect）

### 6.1 别名规则（join 与子查询的正确性根基）

| 场景 | 别名 |
|---|---|
| 纯单表（无 join） | **不使用别名**，SQL 保持 `SELECT * FROM t_user WHERE ...` 干净形态 |
| join 参与表 | 根表 `t0`，按 join 加入顺序 `t1, t2, ...`；条件列渲染为 `t1.user_id` |
| 子查询（深度 d≥1） | 内部别名 `s{d}_0, s{d}_1, ...`，与外层 `t{n}` 不冲突；相关子查询引用外层列直接用外层别名 |

- **列归属解析**：lambda → `LambdaUtils.getImplClass()` → 注册表（类→TableRef）→ `别名.列名`。lambda 类不在注册表 → SqlBuildException（消息列出当前可用实体，指明“忘记 join”）。
- **同实体重复 join**（自连接）：v0.1 抛 SqlBuildException（类→别名映射无法消歧）；v0.2 起以 `QueryTable` 显式别名方案支持（§5.9）。

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
| SEQUENCE nextval | ❌（抛 SqlBuildException） | `SELECT nextval('seq')` | `SELECT NEXT VALUE FOR "seq"` |
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
- SEQUENCE 主键：单条 insert 前发一条 `nextval` SELECT 回填实体，id 随 INSERT 列写入（无 JDBC 生成键回填）；
  insertBatch 逐实体取 `nextval` 后再批量插入（N 条额外 SELECT，不支持 pooled 预分配）。
- `@Version`：`update/updateSelective/delete(entity)` 的 WHERE 追加 `version = ?`，SET 追加
  `version = version + ?`（逻辑删除转 UPDATE 时同样追加自增）；影响行数 ≠ 1 →
  `jakarta.persistence.OptimisticLockException`（标准类型，不新增自定义异常），成功后新版本号回填实体；
  insert 时版本号为 null 则初始化为 0 并回填。
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

乐观锁冲突复用标准类型 `jakarta.persistence.OptimisticLockException`（extends PersistenceException，
unchecked）——不新增自定义异常类型。
```

消息规范：一句话说明问题 + 如何修复（如 “Property `orders` referenced by lambda is not part of this query. Available: t_user, t_order. Did you forget to join?”）。

---

## 9. 线程安全与生命周期

| 对象 | 线程安全 | 生命周期 |
|---|---|---|
| LightQuery（静态门面）/ LightQuerySession / Dialect / EntityMeta | ✅ 不可变（门面注册表并发安全） | JVM 级单例共享 |
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
| T11 | MultiDataSourceH2Test | 静态门面与多数据源：默认走主库、`datasource(name)` / 内联 `datasource(name, ds)` 指定（get-or-register 缓存复用）、未知名报错（含可用名称）、未注册主库报错、重复注册冲突、空白名拒绝、指定数据源的事务、主库事务内指定其他数据源在事务外执行、按数据源方言（含显式方言路由）、`of` 独立会话不进注册表 |
| T12 | OptimisticLockH2Test | `@Version`：insert 版本初始化、update 追加版本校验并自增（回填新版本）、updateSelective、delete（含逻辑删除转 UPDATE）、版本冲突抛 OptimisticLockException、非法版本类型/多 @Version 启动报错 |
| T13 | SequenceH2Test | SEQUENCE 主键：nextval 回填后随 INSERT 写入、insertBatch 逐实体取值、方言 SQL 形态（PG nextval / H2 NEXT VALUE FOR）、MySQL 不支持报错、缺 @SequenceGenerator 启动报错 |
| T14 | SelfJoinH2Test | 自连接：同实体多次 join 显式别名、TableColumn 条件/投影/排序、未注册 occurrence 报错、重复实体 lambda 条件报错（提示 TableColumn）、重复别名/保留别名拒绝、逻辑删过滤只在根表 |
| T15 | FillListenerH2Test | 填充 SPI：insert/update 回调、批量逐实体回调、未注册时无副作用、监听器异常原样传播 |
| T16 | SpringStarterTest | starter：自动装配 LightQuerySession Bean + 门面主库注册、Spring 事务内语句复用绑定连接（rollback 生效）、无事务时逐操作连接、自定义 Bean 跳过自动装配 |

覆盖率门禁：JaCoCo core 指令覆盖 ≥ 85%，`sqlgen`/`meta` 包 ≥ 90%。

---

## 12. 开源工程化（GitHub 就绪清单）

### 12.1 仓库目录

```
light-query-parent/
├── light-query/                  core 模块
│   └── src/ main|test
├── light-query-spring-boot-starter/   v0.2 起（可选依赖 Spring Boot 3.x）
│   └── src/ main|test
├── docs/ DESIGN.md(本文) API.md(按特性 cookbook+生成SQL) ROADMAP.md RELEASE.md(发布手册)
├── .github/workflows/ build.yml release.yml
├── .github/ ISSUE_TEMPLATE …
├── README.md CONTRIBUTING.md LICENSE CHANGELOG.md pom.xml(聚合)
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
| **M3 join/子查询/聚合/分页** | TableRef 别名注册/JoinOn/子查询(相关)/Aggregations/Tuple/PageResult | T3 join 部分 + T6 + 分页用例全绿 |
| **M4 事务与健壮性** | inTransaction/异常体系/consumed 防复用/fetchSize | T8 T10 全绿 |
| **M5 开源发布** | CI/文档全套/发布 profile/README | CI 全绿 + 文档审阅 + 本地 Central 干跑 |

当前进度：M1 已完成约 50%（注解层需按本文档替换为 jakarta 并重跑）。

---

## 14. Roadmap

| 版本 | 内容 |
|---|---|
| v0.2（本轮已实现） | `@Version` 乐观锁、`SEQUENCE` 主键、自连接（QueryTable/TableColumn）、spring-boot-starter（SpringConnectionProvider 对接 Spring 事务）、字段自动填充监听器 SPI（FillListener） |
| v0.3 | Oracle/SQLServer/达梦方言、seek 分页（逻辑分页）、VO/record 投影 select、审计拦截器 SPI、update join / delete join |
| v1.0 | API 冻结、长期兼容承诺、性能基准报告（JMH）、多驱动兼容矩阵 |
