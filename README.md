# light-query

会写 Java lambda 就会用的 ORM：JDK 21 + Jakarta Persistence 标准注解 + 链式查询。
唯一强制运行时依赖是 `jakarta.persistence-api`（纯注解，无传递依赖），Spring 集成是可选模块。

[![Build](https://github.com/snowxuyu/light-query/actions/workflows/build.yml/badge.svg)](https://github.com/snowxuyu/light-query/actions/workflows/build.yml)
![JDK](https://img.shields.io/badge/JDK-21-blue)
![Maven Central](https://img.shields.io/maven-central/v/io.github.snowxuyu/light-query)
![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)

## 安装

```xml
<dependency>
    <groupId>io.github.snowxuyu</groupId>
    <artifactId>light-query</artifactId>
    <version>0.3.0</version>
</dependency>

<!-- Spring Boot 环境（可选）：自动装配 + Spring 事务对接 -->
<dependency>
    <groupId>io.github.snowxuyu</groupId>
    <artifactId>light-query-spring-boot-starter</artifactId>
    <version>0.3.0</version>
</dependency>
```

## 快速上手

> 📖 **完整使用文档见 [docs/USAGE.md](docs/USAGE.md)**——面向第一次使用者，从安装到每个功能的用法和示例。

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

应用启动入口注册一次数据源，之后**任何地方直接静态调用**，无需持有实例：

```java
LightQuery.primary(dataSource);       // 方言从 JDBC URL 自动探测
```

```java
// 查询
List<User> users = LightQuery.queryable(User.class)
    .col(User::getStatus).eq(Status.ACTIVE)
    .and(w -> w.col(User::getName).like("frank").or().col(User::getAge).ge(18))
    .orderByDesc(User::getId)
    .limit(10)
    .toList();

// 写入
LightQuery.insert(user);                       // 自增主键回填
LightQuery.updateSelective(user);              // 非空字段按主键更新
LightQuery.deleteById(User.class, 1L);

// 事务：正常返回 → commit；异常 → rollback 并原样抛出
Long orderId = LightQuery.inTransaction(tx -> {
    tx.insert(order);
    tx.updatable(Account.class).setIncrement(Account::getBalance, -amount)
      .col(Account::getId).eq(accountId).execute();
    return order.getId();
});
```

## 数据源

单数据源：注册一次，全部调用默认走它。多数据源：注册命名数据源，调用点显式指定（无任何自动路由）。

```java
LightQuery.primary(dsMain);                    // 主数据源 = 默认目标
LightQuery.datasource("order", dsOrder);       // 命名数据源，方言自动探测，也可显式传入

LightQuery.queryable(User.class)...                             // → 主数据源
LightQuery.datasource("order").queryable(Order.class)...        // → order 数据源
LightQuery.datasource("order", dsOrder).queryable(Order.class)  // 内联注册 + 使用，等价
```

- `datasource(...)` 返回绑定该库的 `LightQuerySession`（能力完整），同名同库重复调用复用同一会话
- 未知名直接报错并列出可用名称；首次注册即探测方言，配错即失败
- 偏好依赖注入的团队可绕开门面：`LightQuery.of(ds)` 创建独立会话直接持有

## 查询

### 条件

先调 `col(...)` 拿到列柄（值类型由属性 lambda 在编译期锁定，写错直接编不过），再在柄上写条件：

```java
LightQuery.queryable(User.class)
    .col(User::getStatus).eq(Status.ACTIVE)       // null → IS NULL；ne → IS NOT NULL
    .col(User::getName).in(List.of("a", "b"))     // 空集合 → 1 = 0（防全表事故）
    .col(User::getName).notIn(List.of())          // 空 NOT IN → 1 = 1
    .col(User::getAge).between(18, 60)            // 比较族：gt/ge/lt/le/between/notBetween
    .col(User::getName).like("frank")             // \ % _ 自动转义，两侧加 %
    .col(User::getRemark).isNull()
    .and(w -> w.col(User::getAge).ge(18).or().col(User::getAge).lt(12))
    .toList();
```

同一柄上还有：列对列 `eqColumn/neColumn/gtColumn/...`（两侧同类型才编过）、子查询
`in(subQuery)/eqSubQuery/...`、文本 `notLike/startsWith/endsWith`（仅 String 列有意义）；
`updatable` 的柄另带 `set/setNull/setIncrement`（`setIncrement` 仅数值列有意义）。
`sum/avg/max/min` 聚合终端要求 `Number` 属性。
