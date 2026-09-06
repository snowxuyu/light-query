# light-query

会写 Java lambda 就会用的 ORM：JDK 21 + Jakarta Persistence 标准注解 + 链式查询。
唯一强制运行时依赖是 `jakarta.persistence-api`（纯注解，无传递依赖），Spring 集成是可选模块。

[![Build](https://github.com/snowxuyu/light-query/actions/workflows/build.yml/badge.svg)](https://github.com/snowxuyu/light-query/actions/workflows/build.yml)
![JDK](https://img.shields.io/badge/JDK-21-blue)
![Maven Central](https://img.shields.io/maven-central/v/io.github.snowxuyu/light-query)
![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)

## 为什么是 light-query

| | mybatis-dynamic-query | easy-query | **light-query** |
|---|---|---|---|
| API | FilterDescriptor 描述符 | 链式 lambda | 链式 lambda（MyBatis-Plus 风格）+ 静态门面 |
| 编译期要求 | 无 | APT 生成代理 | **无**（运行时解析 lambda） |
| 运行时依赖 | MyBatis + Jackson + commons-lang3 | 自研较多 | **仅 jakarta.persistence-api** |
| 实体注解 | 自定义 | 自定义 | **JPA 标准注解** |
| 多数据源 | 需自行组装 | 需自行组装 | **门面内置：注册一次，按名切换** |
| JDK | 8 | 8+ | **21** |

## 安装

```xml
<dependency>
    <groupId>io.github.snowxuyu</groupId>
    <artifactId>light-query</artifactId>
    <version>0.2.0</version>
</dependency>

<!-- Spring Boot 环境（可选）：自动装配 + Spring 事务对接 -->
<dependency>
    <groupId>io.github.snowxuyu</groupId>
    <artifactId>light-query-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

## 30 秒上手

实体用 JPA 标准注解（唯一自定义注解是 `@LogicDelete`）：

```java
@Table(name = "t_user")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_name")
    private String name;

    @Enumerated(EnumType.STRING)      // 枚举默认 ORDINAL，务必显式标注！
    private Status status;

    @LogicDelete                      // 逻辑删除：查询自动过滤，delete 自动转 UPDATE
    private Integer deleted;

    @Transient
    private String ignored;
    // getter / setter
}
```

启动时注册一次数据源，之后**任何地方直接静态调用**，无需持有实例：

```java
// 应用启动入口执行一次；方言从 JDBC URL 自动探测
LightQuery.primary(dataSource);
```

```java
// 查询
List<User> users = LightQuery.queryable(User.class)
    .eq(User::getStatus, Status.ACTIVE)
    .and(w -> w.like(User::getName, "frank").or().ge(User::getAge, 18))
    .orderByDesc(User::getId)
    .limit(10)
    .toList();

// 写入
LightQuery.insert(user);                       // 自增主键回填
LightQuery.updateSelective(user);              // 非空字段按主键更新
LightQuery.deleteById(User.class, 1L);

// 事务：异常自动回滚并原样抛出
Long orderId = LightQuery.inTransaction(tx -> {
    tx.insert(order);
    tx.updatable(Account.class).setIncrement(Account::getBalance, -amount)
      .eq(Account::getId, accountId).execute();
    return order.getId();
});
```

## 核心概念：静态门面与 LightQuerySession

- `LightQuery` 是**静态门面**：`primary(ds)` 注册主数据源（所有未指定数据源调用的默认目标），
  之后 `LightQuery.queryable(...)`、`LightQuery.insert(...)`、`LightQuery.inTransaction(...)`
  直接可用——单数据源应用全程不需要实例变量，也不需要任何切换调用。
- `LightQuerySession` 是绑定一个数据源（或一个事务连接）的**不可变会话**，承载全部能力。
  门面的每次调用都委托给注册的会话；`inTransaction` 的 lambda 拿到的也是会话。
- 偏好依赖注入的团队可以绕开门面：`LightQuery.of(ds)` 创建独立会话直接持有（可替换、可测试）。

## 查询

条件平铺为 AND，`and()/or()` 分组，组内 `.or()` 切换连接符（MyBatis-Plus 语义）：

```java
LightQuery.queryable(User.class)
    .eq(User::getStatus, Status.ACTIVE)          // null → IS NULL；ne → IS NOT NULL
    .in(User::getName, List.of("a", "b"))        // 空集合 → 1 = 0（防全表事故）
    .notIn(User::getName, List.of())             // 空 NOT IN → 1 = 1
    .between(User::getAge, 18, 60)
    .like(User::getName, "frank")                // \ % _ 自动转义，两侧加 %
    .isNull(User::getRemark)
    .and(w -> w.ge(User::getAge, 18).or().lt(User::getAge, 12))
    .toList();
```

排序与分页——两种方式：

```java
// ① offset 分页（实现简单，深页有扫描成本）
PageResult<User> page = LightQuery.queryable(User.class)
    .orderByAsc(User::getId)
    .toPageResult(1, 20);                        // total/pages 一并返回

// ② seek 逻辑分页（keyset，深页 O(1)，游标来自上一页最后一行）
List<User> next = LightQuery.queryable(User.class)
    .orderByAsc(User::getId)
    .seekAfter(lastId)                           // 多列/混合方向：值与排序列一一对应
    .limit(20)
    .toList();
```

投影——三种出口：

```java
// 1) Tuple：标签 = 列名或别名
List<Tuple> rows = LightQuery.queryable(Order.class)
    .select(Order::getUserId, Aggregations.count().as("cnt"))
    .groupBy(Order::getUserId)
    .toTupleList();

// 2) VO / record 投影：按名字匹配结果集标签（忽略大小写与下划线），类型自动转换
record OrderStat(Long userId, Long orderCount, BigDecimal totalAmount) {}
List<OrderStat> stats = LightQuery.queryable(Order.class)
    .select(Order::getUserId)
    .select(Aggregations.count().as("orderCount"),
            Aggregations.sum(Order::getAmount).as("totalAmount"))
    .groupBy(Order::getUserId)
    .toList(OrderStat.class);

// 3) 标量聚合终端
Number total = LightQuery.queryable(Order.class).sum(Order::getAmount);
```

多表 join 与子查询：

```java
// lambda 的声明类自动解析表别名（t0/t1），引用未 join 的实体会直接报错并提示
List<Tuple> rows = LightQuery.queryable(User.class)
    .leftJoin(Order.class, on -> on.eq(User::getId, Order::getUserId))
    .gt(Order::getAmount, new BigDecimal("100"))
    .select(User::getName, Aggregations.sum(Order::getAmount).as("total"))
    .groupBy(User::getName)
    .orderByDesc("total")
    .toTupleList();

// 子查询（IN / EXISTS / 标量），相关子查询直接引用外层 lambda
List<User> bigSpenders = LightQuery.queryable(User.class)
    .in(User::getId, LightQuery.queryable(Order.class).select(Order::getUserId))
    .toList();
```

自连接——同一实体出现多次时，用 `QueryTable` 命名每次出现，`TableColumn` 消歧：

```java
QueryTable<Employee> staff = QueryTable.of(Employee.class, "staff");
QueryTable<Employee> manager = QueryTable.of(Employee.class, "mgr");

List<Tuple> pairs = LightQuery.queryable(staff)
    .leftJoin(manager, on -> on.eqColumn(
        staff.col(Employee::getManagerId), manager.col(Employee::getId)))
    .eq(manager.col(Employee::getName), "Alice")
    .select(staff.col(Employee::getName), manager.col(Employee::getName).as("manager_name"))
    .toTupleList();
```

## 写入

```java
LightQuery.insert(user);              // IDENTITY 自增回填；@Version 为 null 时初始化 0
LightQuery.insertBatch(list);         // JDBC batch（SEQUENCE 主键会逐实体取 nextval）
LightQuery.update(user);              // 按 PK 全量更新（null 列写 NULL）
LightQuery.updateSelective(user);     // 按 PK 只更新非 null 列
LightQuery.delete(user);              // 有 @LogicDelete → UPDATE deleted=1
LightQuery.deleteById(User.class, 1L);// 幂等：0 行返回 0 不抛异常
```

链式更新/删除带全表保护：

```java
int rows = LightQuery.updatable(User.class)
    .set(User::getStatus, Status.FROZEN)
    .setIncrement(User::getAge, 1)
    .eq(User::getId, 5)
    .execute();                       // 无条件执行需要显式 allowFullTable()
```

### 乐观锁（@Version）

数值版本字段（int/Integer/long/Long）自动参与实体级 `update / updateSelective / delete`：

```java
db.update(doc);   // WHERE ... AND version = ?，SET ... , version = version + 1
                  // 版本冲突 → jakarta.persistence.OptimisticLockException
                  // 成功后新版本号回填实体；insert 时 null 版本初始化为 0
```

### SEQUENCE 主键

```java
@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "invoice_seq_gen")
@SequenceGenerator(name = "invoice_seq_gen", sequenceName = "invoice_seq")
private Long id;
// 插入前自动取 nextval 回填实体，id 随 INSERT 写入（PG / Oracle / H2 / SQL Server）
// MySQL 不支持序列，会在启动后的第一次插入时报错提示
```

### 字段自动填充（FillListener SPI）

```java
LightQuery.setFillListener(new FillListener() {
    public void onInsert(Object entity) {
        if (entity instanceof User u) u.setCreatedAt(LocalDateTime.now());
    }
    public void onUpdate(Object entity) {
        if (entity instanceof User u) u.setUpdatedAt(LocalDateTime.now());
    }
});
```

## 多数据源

门面内置注册表：主数据源是默认目标，命名数据源在调用点显式指定（无任何自动路由）。

```java
// 启动时注册
LightQuery.primary(dsMain);
LightQuery.datasource("order", dsOrder);       // 方言自动探测，也可显式传入

// 单数据源：任何调用无需指定
LightQuery.queryable(User.class)...            // → 主数据源

// 多数据源：调用点指定，两种写法等价
LightQuery.datasource("order", dsOrder).queryable(Order.class)  // 内联注册 + 使用
LightQuery.datasource("order").queryable(Order.class)           // 按名解析（已注册时）
```

- `datasource(...)` 返回绑定该库的 `LightQuerySession`（能力完整），同名同库重复调用复用同一会话
- 未知名直接报错并列出可用名称；首次注册即探测方言，配错即失败
- 主库事务内经 `LightQuery.datasource("order")` 发出的语句运行在该事务之外（跨库无分布式事务）

## 事务

```java
Long orderId = LightQuery.inTransaction(tx -> {
    tx.insert(order);
    tx.updatable(Account.class).setIncrement(Account::getBalance, -amount)
      .eq(Account::getId, accountId).execute();
    return order.getId();
});   // 正常返回 → commit；异常 → rollback 并原样抛出；嵌套调用复用外层连接
```

指定数据源的事务：`LightQuery.datasource("order").inTransaction(tx -> ...)`。
**Spring Boot Starter** 环境下语句自动加入 Spring 事务（`@Transactional` 内不自行 commit/rollback）。

## Spring Boot Starter

```xml
<dependency>
    <groupId>io.github.snowxuyu</groupId>
    <artifactId>light-query-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

- 容器存在唯一候选 `DataSource` 时自动装配：注册 `LightQuerySession` Bean 并同步到静态门面
- 语句自动参与 Spring 事务；无事务时逐操作取池化连接
- 自定义 `LightQuerySession` Bean 会跳过自动装配；多数据源应用手动注册（见上节）

## 语义约定（写死并测试固化）

| 场景 | 行为 |
|---|---|
| `eq(col, null)` | 渲染为 `col IS NULL`；`ne` → `IS NOT NULL` |
| 空 `IN` / 空 `NOT IN` | `1 = 0` / `1 = 1`（防全表事故） |
| `like` | 值自动转义 `\ % _` 后两侧加 `%`；PG/H2/Oracle/SQLServer 输出 `ESCAPE '\'` |
| 逻辑删除 | 查询/更新自动追加过滤；`includeDeleted()` / `physical()` 逃生门 |
| 无条件 UPDATE/DELETE | 直接拒绝，需显式 `allowFullTable()` |
| join 行为 | 标准 SQL 语义：一个订单匹配一行则根实体出现一次 |
| `count()` | 忽略排序与分页；有 groupBy 时子查询计数 |
| `sum/avg` | BigDecimal（空表 sum=0、avg=null）；`max/min` 驱动原生数值 |
| Queryable 复用 | 终端方法消费后不可再用（抛异常），每次新建 |
| 多数据源 | 默认走主数据源；调用点 `datasource(...)` 显式指定 |
| `@Version` 乐观锁 | update/delete(entity) 追加版本校验并自增；冲突抛 `OptimisticLockException` |
| SEQUENCE 主键 | 插入前取 `nextval` 回填；MySQL 抛 `SqlBuildException` |
| 自连接 | 同实体多次出现必须 `QueryTable` + `TableColumn`；重复实体上的普通 lambda 直接报错 |
| 投影映射 | 组件/属性名与标签按"忽略大小写与下划线"匹配；缺列报错并列出可用标签 |
| seek 分页 | 值与排序列一一对应且非 null；无 orderBy 直接报错 |

## 当前支持

- 数据库：MySQL / MariaDB / PostgreSQL / H2 / **Oracle (12c+)** / **SQL Server (2012+)**
- 主键：`@Id`（可复合）+ IDENTITY 自增回填 + SEQUENCE（`@SequenceGenerator`）
- 类型：基础类型、BigDecimal/BigInteger、枚举（`@Enumerated`）、`java.util.Date` 与 java.time 全家桶
- 查询：全操作符、分组聚合（`Aggregations`）、子查询、join、自连接（`QueryTable`）、offset + seek 两种分页、
  VO/record 投影、`FOR UPDATE`、动态表名 `asTable`
- 乐观锁：`@Version`（数值版本，实体级 update/delete 自动参与）
- 自动填充：`FillListener` SPI
- 多数据源：静态门面注册主库 + 命名数据源，各数据源独立方言
- 事务：编程式 `inTransaction`（嵌套复用外层连接）+ Spring 事务对接（starter）

## 常见问题

**枚举忘了 `@Enumerated` 会怎样？** 遵循 JPA 默认 `ORDINAL` 存储（存数字）。这是 JPA 规范行为，但容易踩坑，建议总是显式标注 `@Enumerated(EnumType.STRING)`。

**深分页怎么处理？** 用 seek 逻辑分页（`seekAfter`）替代大 offset：游标来自上一页最后一行的排序值，数据库走索引扫描，代价与页深无关。

**MySQL 能用 SEQUENCE 主键吗？** 不能。MySQL 没有序列，实体配置 SEQUENCE 后首次插入会抛 `SqlBuildException`；MySQL 请使用 IDENTITY。

**多数据源下如何指定方言？** 注册时自动探测；探测失败或需要覆盖时使用重载传入 `Dialect`：
`LightQuery.datasource("report", dsReport, new OracleDialect())`。

**如何参与开发？** 见 [CONTRIBUTING.md](CONTRIBUTING.md)。设计契约与测试矩阵见 [docs/DESIGN.md](docs/DESIGN.md)——**任何实现偏离该文档都视为 bug**。

## 路线图

| 版本 | 状态 | 内容 |
|---|---|---|
| 0.2.0 | ✅ 已发布 | 静态门面 + 多数据源、`@Version` 乐观锁、SEQUENCE 主键、自连接、FillListener、Spring Boot Starter |
| 0.3.0 | 🚧 开发中（[`dev/0.3.0`](https://github.com/snowxuyu/light-query/tree/dev/0.3.0) 分支） | ✅ VO/record 投影、✅ seek 逻辑分页、✅ Oracle/SQLServer 方言；规划中：审计拦截器 SPI、update join / delete join、达梦方言 |
| 1.0 | 规划 | API 冻结、长期兼容承诺、性能基准报告（JMH） |

## License

[Apache-2.0](LICENSE)
