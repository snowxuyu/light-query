# light-query

会写 Java lambda 就会用的 ORM：JDK 21 + Jakarta Persistence 标准注解 + 链式查询。
唯一的运行时依赖是 `jakarta.persistence-api`（纯注解，无传递依赖）。

[![Build](https://github.com/snowxuyu/light-query/actions/workflows/build.yml/badge.svg)](https://github.com/snowxuyu/light-query/actions/workflows/build.yml)
![JDK](https://img.shields.io/badge/JDK-21-blue)
![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)

## 为什么是 light-query

| | mybatis-dynamic-query | easy-query | **light-query** |
|---|---|---|---|
| API | FilterDescriptor 描述符 | 链式 lambda | 链式 lambda（MyBatis-Plus 风格） |
| 编译期要求 | 无 | APT 生成代理 | **无**（运行时解析 lambda） |
| 运行时依赖 | MyBatis + Jackson + commons-lang3 | 自研较多 | **仅 jakarta.persistence-api** |
| 实体注解 | 自定义 | 自定义 | **JPA 标准注解** |
| JDK | 8 | 8+ | 21 |

## 30 秒上手

```xml
<dependency>
    <groupId>io.github.snowxuyu</groupId>
    <artifactId>light-query</artifactId>
    <version>0.1.0</version>
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
LightQuery db = LightQuery.of(dataSource);   // 方言从 JDBC URL 自动探测
```

查询：

```java
// 条件平铺为 AND；and()/or() 分组；组内 .or() 切换连接符
List<User> users = db.queryable(User.class)
    .eq(User::getStatus, Status.ACTIVE)
    .and(w -> w.like(User::getName, "frank").or().ge(User::getAge, 18))
    .between(User::getCreatedAt, t1, t2)
    .orderByDesc(User::getCreatedAt)
    .limit(10)
    .toList();

User one = db.queryable(User.class).eq(User::getId, 1L).firstOrNull();
long total = db.queryable(User.class).eq(User::getStatus, Status.ACTIVE).count();
PageResult<User> page = db.queryable(User.class).toPageResult(1, 20);
```

多表 join 与子查询：

```java
// lambda 的声明类自动解析表别名（t0/t1），引用未 join 的实体会直接报错并提示
List<Tuple> rows = db.queryable(User.class)
    .leftJoin(Order.class, on -> on.eq(User::getId, Order::getUserId))
    .gt(Order::getAmount, new BigDecimal("100"))     // 条件可引用任意参与表
    .select(User::getName).select(F.sum(Order::getAmount).as("total"))
    .groupBy(User::getName)
    .orderByDesc("total")
    .toTupleList();

// 子查询（IN / EXISTS / 标量），相关子查询直接引用外层 lambda
List<User> bigSpenders = db.queryable(User.class)
    .in(User::getId, db.queryable(Order.class).select(Order::getUserId))
    .toList();
```

CRUD 与事务：

```java
db.insert(user);                    // 自增主键回填
db.insertBatch(list);               // JDBC batch
db.updateSelective(user);           // 非空字段按主键更新
db.delete(user);                    // 有 @LogicDelete → UPDATE deleted=1
db.deleteById(User.class, 1L);

int rows = db.updatable(User.class)
    .set(User::getStatus, Status.FROZEN)
    .setIncrement(User::getAge, 1)
    .eq(User::getId, 5)
    .execute();                     // 无条件执行需要显式 allowFullTable()

Long orderId = db.inTransaction(tx -> {
    tx.insert(order);
    tx.updatable(Account.class).setIncrement(Account::getBalance, -amount)
      .eq(Account::getId, accountId).execute();
    return order.getId();
});                                 // 异常自动回滚并原样抛出
```

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

## 当前支持

- 数据库：MySQL / MariaDB / PostgreSQL / H2（方言接口可扩展）
- 主键：`@Id`（可复合）+ `@GeneratedValue(IDENTITY)` 自增回填
- 类型：基础类型、BigDecimal/BigInteger、枚举（`@Enumerated`）、`java.util.Date` 与 java.time 全家桶
- 查询：全操作符、分组聚合（`F`）、子查询、join（inner/left/right）、分页、`FOR UPDATE`、动态表名 `asTable`
- 事务：编程式 `inTransaction`（嵌套复用外层连接）

Roadmap（v0.2+）：`@Version` 乐观锁、SEQUENCE 主键、自连接、Spring Boot Starter、Oracle/SQLServer 方言。
详见 [docs/DESIGN.md](docs/DESIGN.md)。

## 常见问题

**枚举忘了 `@Enumerated` 会怎样？** 遵循 JPA 默认 `ORDINAL` 存储（存数字）。这是 JPA 规范行为，但容易踩坑，建议总是显式标注 `@Enumerated(EnumType.STRING)`。

**MySQL 流式查询？** 大结果集请用 `limit/offset` 分批；`fetchSize` 支持在 roadmap。

**如何参与开发？** 见 [CONTRIBUTING.md](CONTRIBUTING.md)。设计契约与测试矩阵见 [docs/DESIGN.md](docs/DESIGN.md)——**任何实现偏离该文档都视为 bug**。

## License

[Apache-2.0](LICENSE)
