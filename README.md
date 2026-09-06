# light-query

会写 Java lambda 就会用的 ORM：JDK 21 + Jakarta Persistence 标准注解 + 链式查询。
唯一的运行时依赖是 `jakarta.persistence-api`（纯注解，无传递依赖）。

[![Build](https://github.com/snowxuyu/light-query/actions/workflows/build.yml/badge.svg)](https://github.com/snowxuyu/light-query/actions/workflows/build.yml)
![JDK](https://img.shields.io/badge/JDK-21-blue)
![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)

## 30 秒上手

```xml
<dependency>
    <groupId>io.github.snowxuyu</groupId>
    <artifactId>light-query</artifactId>
    <version>0.2.0</version>
</dependency>
```

实体使用标准 JPA 注解（唯一自定义注解是 `@LogicDelete`）：

```java
@Table(name = "t_user")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_name")
    private String name;

    @Enumerated(EnumType.STRING)
    private Status status;          // 枚举默认 ORDINAL，务必显式标注！

    @LogicDelete                    // 逻辑删除：查询自动过滤，delete 自动转 UPDATE
    private Integer deleted;

    @Transient
    private String ignored;
    // getter / setter
}
```

创建入口（应用启动时一次，线程安全，全局复用）：

```java
LightQuery.primary(dataSource);              // 注册主数据源；方言从 JDBC URL 自动探测
```

查询（任意位置直接静态调用，无需持有实例）：

```java
// 条件平铺为 AND；and()/or() 分组；组内 .or() 切换连接符
List<User> users = LightQuery.queryable(User.class)
    .eq(User::getStatus, Status.ACTIVE)
    .and(w -> w.like(User::getName, "frank").or().ge(User::getAge, 18))
    .between(User::getCreatedAt, t1, t2)
    .orderByDesc(User::getCreatedAt)
    .limit(10)
    .toList();

User one = LightQuery.queryable(User.class).eq(User::getId, 1L).firstOrNull();
long total = LightQuery.queryable(User.class).eq(User::getStatus, Status.ACTIVE).count();
PageResult<User> page = LightQuery.queryable(User.class).toPageResult(1, 20);
```

多表 join 与子查询：

```java
// lambda 的声明类自动解析表别名（t0/t1），引用未 join 的实体会直接报错并提示
List<Tuple> rows = LightQuery.queryable(User.class)
    .leftJoin(Order.class, on -> on.eq(User::getId, Order::getUserId))
    .gt(Order::getAmount, new BigDecimal("100"))     // 条件可引用任意参与表
    .select(User::getName).select(Aggregations.sum(Order::getAmount).as("total"))
    .groupBy(User::getName)
    .orderByDesc("total")
    .toTupleList();

// 子查询（IN / EXISTS / 标量），相关子查询直接引用外层 lambda
List<User> bigSpenders = LightQuery.queryable(User.class)
    .in(User::getId, LightQuery.queryable(Order.class).select(Order::getUserId))
    .toList();
```

CRUD 与事务：

```java
LightQuery.insert(user);                    // 自增主键回填
LightQuery.insertBatch(list);               // JDBC batch
LightQuery.updateSelective(user);           // 非空字段按主键更新
LightQuery.delete(user);                    // 有 @LogicDelete → UPDATE deleted=1
LightQuery.deleteById(User.class, 1L);

int rows = LightQuery.updatable(User.class)
    .set(User::getStatus, Status.FROZEN)
    .setIncrement(User::getAge, 1)
    .eq(User::getId, 5)
    .execute();                     // 无条件执行需要显式 allowFullTable()

Long orderId = LightQuery.inTransaction(tx -> {
    tx.insert(order);
    tx.updatable(Account.class).setIncrement(Account::getBalance, -amount)
      .eq(Account::getId, accountId).execute();
    return order.getId();
});                                 // 异常自动回滚并原样抛出；tx 绑定事务连接
```

多数据源：

```java
// 启动时注册主数据源（默认目标）与命名数据源
LightQuery.primary(dsMain);
LightQuery.datasource("order", dsOrder);       // 方言自动探测，也可显式传入

// 单数据源：任何调用无需指定（默认走主数据源）
LightQuery.queryable(User.class)...            // → 主数据源

// 多数据源：调用点指定数据源，两种写法等价
LightQuery.datasource("order", dsOrder).queryable(Order.class)  // 内联注册 + 使用
LightQuery.datasource("order").queryable(Order.class)           // 按名解析（已注册时）
```

- `datasource(...)` 返回绑定该库的 `LightQuerySession`（能力完整：CRUD / `inTransaction` / join…），不可变、线程安全；同名同库重复调用复用同一会话
- 未知名直接报错并列出可用名称；首次注册即探测方言，配错即失败
- 主库事务内经 `LightQuery.datasource("order")` 发出的语句运行在该事务之外（跨库无分布式事务）；不做读写分离等自动路由——指定永远是显式的
- 偏好依赖注入的团队可绕开门面：`LightQuery.of(ds)` 创建独立的 `LightQuerySession` 直接持有

v0.2 新能力：

```java
// 乐观锁：@Version 字段自动参与 update/delete(entity)
db.update(doc);          // WHERE ... AND version = ?，SET version = version + 1
                         // 版本冲突 → jakarta OptimisticLockException，新版本回填实体

// SEQUENCE 主键：@GeneratedValue(strategy = SEQUENCE) + @SequenceGenerator(sequenceName = "invoice_seq")
// 插入前自动取 nextval 回填实体（PG / H2 支持，MySQL 抛 SqlBuildException）

// 自连接：QueryTable 显式命名每次出现，TableColumn 消歧
QueryTable<Employee> manager = QueryTable.of(Employee.class, "mgr");
QueryTable<Employee> staff   = QueryTable.of(Employee.class, "staff");
List<Tuple> rows = LightQuery.queryable(staff)
    .leftJoin(manager, on -> on.eqColumn(
        staff.col(Employee::getManagerId), manager.col(Employee::getId)))
    .select(staff.col(Employee::getName), manager.col(Employee::getName).as("manager_name"))
    .toTupleList();

// 字段自动填充：全局 SPI，insert/update 前回调
LightQuery.setFillListener(new FillListener() {
    public void onInsert(Object entity) {
        if (entity instanceof User u) u.setCreatedAt(java.time.LocalDateTime.now());
    }
});
```

### Spring Boot Starter（v0.2 起）

```xml
<dependency>
    <groupId>io.github.snowxuyu</groupId>
    <artifactId>light-query-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

- 容器存在唯一候选 `DataSource` 时自动装配：注册 `LightQuerySession` Bean 并同步到静态门面（零配置直用 `LightQuery.queryable(...)`）
- 语句自动参与 Spring 事务（`@Transactional` 内不自行 commit/rollback，交由事务管理器）；无事务时逐操作取池化连接
- 自定义 `LightQuerySession` Bean 会跳过自动装配；多数据源应用按上文手动注册

## 语义约定（写死并测试固化）

| 场景 | 行为 |
|---|---|
| `eq(col, null)` | 渲染为 `col IS NULL`；`ne` → `IS NOT NULL` |
| 空 `IN` / 空 `NOT IN` | `1 = 0` / `1 = 1`（防全表事故） |
| `like` | 值自动转义 `\ % _` 后两侧加 `%`；PG/H2 输出 `ESCAPE '\'` |
| 逻辑删除 | 查询/更新自动追加过滤；`includeDeleted()` / `physical()` 逃生门 |
| 无条件 UPDATE/DELETE | 直接拒绝，需显式 `allowFullTable()` |
| join 行为 | 标准 SQL 语义：一个订单匹配一行则根实体出现一次 |
| `count()` | 忽略排序与分页；有 groupBy 时子查询计数 |
| `sum/avg` | BigDecimal（空表 sum=0、avg=null）；`max/min` 驱动原生数值 |
| Queryable 复用 | 终端方法消费后不可再用（抛异常），每次新建 |
| 多数据源 | 默认走主数据源；调用点 `datasource(...)` 显式指定；主库事务内指定其他数据源在该事务之外执行 |
| `@Version` 乐观锁 | update/delete(entity) 追加版本校验并自增；冲突抛 `OptimisticLockException`；insert 时 null 初始化为 0 |
| SEQUENCE 主键 | 插入前取 `nextval` 回填实体并随 INSERT 写入；PG/H2 支持，MySQL 抛 `SqlBuildException` |
| 自连接 | 同实体多次出现必须 `QueryTable.of(entity, alias)` + `table.col(...)`；重复实体上的普通 lambda 直接报错 |
| 字段填充 | `FillListener` 在 insert/update 建 SQL 前回调；异常原样传播并使操作失败 |

## 当前支持

- 数据库：MySQL / MariaDB / PostgreSQL / H2（方言接口可扩展）
- 主键：`@Id`（可复合）+ `@GeneratedValue(IDENTITY)` 自增回填 + SEQUENCE（PG/H2）
- 类型：基础类型、BigDecimal/BigInteger、枚举（`@Enumerated`）、`java.util.Date` 与 java.time 全家桶
- 查询：全操作符、分组聚合（`Aggregations`）、子查询、join（inner/left/right）、自连接（`QueryTable`）、分页、`FOR UPDATE`、动态表名 `asTable`
- 乐观锁：`@Version`（数值版本，实体级 update/delete 自动参与）
- 自动填充：`FillListener` SPI（insert/update 实体回调）
- 多数据源：静态门面注册主库（默认）+ 命名数据源，调用点 `datasource(...)` 显式指定，各数据源独立方言
- 事务：编程式 `inTransaction`（嵌套复用外层连接）

Roadmap（v0.3+）：Oracle/SQLServer/达梦方言、seek 分页、VO/record 投影 select、审计拦截器 SPI、update join / delete join。
详见 [docs/DESIGN.md](docs/DESIGN.md)。

## 常见问题

**枚举忘了 `@Enumerated` 会怎样？** 遵循 JPA 默认 `ORDINAL` 存储（存数字）。这是 JPA 规范行为，但容易踩坑，建议总是显式标注 `@Enumerated(EnumType.STRING)`。

**MySQL 流式查询？** 大结果集请用 `limit/offset` 分批；`fetchSize` 支持在 roadmap。

**如何参与开发？** 见 [CONTRIBUTING.md](CONTRIBUTING.md)。设计契约与测试矩阵见 [docs/DESIGN.md](docs/DESIGN.md)——**任何实现偏离该文档都视为 bug**。

## License

[Apache-2.0](LICENSE)
