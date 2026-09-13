# light-query 使用指南

> 面向第一次使用 light-query 的开发者。从零开始，每一步都有完整可运行的代码。

---

## 1. 它是什么

light-query 是一个轻量级 Java ORM：

- **会写 Java lambda 就会用**——条件全部通过方法引用 + 链式调用构建
- **实体用 JPA 标准注解**——`@Table` `@Id` `@Column` `@Transient`，没有自定义实体注解
- **零传递依赖**——唯一依赖是 `jakarta.persistence-api`（纯注解 JAR）
- **强类型**——`col(User::getAge).eq("abc")` 直接编译失败，IDEA 即时标红
- **静态门面**——注册一次数据源，任何地方直接 `LightQuery.queryable(...)`，不需要管理实例

支持数据库：MySQL / MariaDB / PostgreSQL / H2 / Oracle (12c+) / SQL Server (2012+)

---

## 2. 安装

```xml
<dependency>
    <groupId>io.github.snowxuyu</groupId>
    <artifactId>light-query</artifactId>
    <version>0.2.0</version>
</dependency>
```

Spring Boot 项目加 starter（自动装配 + 事务对接）：

```xml
<dependency>
    <groupId>io.github.snowxuyu</groupId>
    <artifactId>light-query-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

---

## 3. 定义实体

用 JPA 标准注解，和 Hibernate/JPA 的实体写法完全一样：

```java
import jakarta.persistence.*;
import com.lightquery.annotation.LogicDelete;

@Table(name = "t_user")
public class User {

    public enum Status { ACTIVE, FROZEN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // 自增主键
    private Long id;

    @Column(name = "user_name")   // 数据库列名，默认驼峰转下划线
    private String name;

    @Enumerated(EnumType.STRING)  // 枚举存字符串（默认 ORDINAL 存数字，务必显式标注！）
    private Status status;

    private Integer age;

    @Column(name = "balance", precision = 12, scale = 2)
    private BigDecimal balance;

    @LogicDelete                  // 唯一自定义注解：逻辑删除
    private Integer deleted;      // 0=正常 1=已删除

    @Transient                    // 非数据库字段
    private String ignored;

    // 必须有无参构造器（反射实例化）
    public User() {}

    // getter / setter 必须有
}
```

### 实体注解速查

| 注解 | 作用 |
|---|---|
| `@Table(name)` | 表名，默认类名驼峰转下划线 |
| `@Id` | 主键（可复合：多个 `@Id` 字段） |
| `@GeneratedValue(IDENTITY)` | 自增，插入后回填 |
| `@GeneratedValue(SEQUENCE)` + `@SequenceGenerator` | 序列（PG/Oracle/H2） |
| `@Column(name, insertable, updatable)` | 列名映射 |
| `@Enumerated(STRING)` | 枚举存字符串 |
| `@Transient` | 不映射 |
| `@Version` | 乐观锁（int/Integer/long/Long） |
| `@LogicDelete` | 逻辑删除标记列 |
| `@Convert(converter=Xxx.class)` | 自定义类型转换器 |

---

## 4. 注册数据源

**应用启动入口**调用一次，之后全局可用。

### 单数据源

```java
public static void main(String[] args) {
    // 方式一：直接传 DataSource（HikariCP / Druid / 容器托管的都行）
    DataSource ds = createHikariDataSource();

    // 注册为主数据源，方言从 JDBC URL 自动探测
    LightQuery.primary(ds);

    // ... 启动其余逻辑
}
```

### 多数据源

```java
// 启动时注册
LightQuery.primary(dsMain);                          // 主数据源 = 默认目标
LightQuery.datasource("order", dsOrder);             // 命名数据源
LightQuery.datasource("report", dsReport);           // 可注册多个
```

```java
// 业务代码里按名切换
LightQuery.queryable(User.class)...                  // → 主数据源
LightQuery.datasource("order").queryable(Order.class)...  // → order 数据源
```

### Spring Boot

引入 starter 后自动装配（容器里只有一个 `DataSource` Bean 时），零配置：

```java
@SpringBootApplication
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
        // starter 自动执行了 LightQuery.primary(dataSource)
    }
}
```

---

## 5. 查询

### 5.1 基础查询

```java
// 全表查询
List<User> users = LightQuery.queryable(User.class).toList();

// 条件查询：col(列) + 条件方法，值类型编译期校验
List<User> actives = LightQuery.queryable(User.class)
    .col(User::getStatus).eq(Status.ACTIVE)
    .col(User::getAge).ge(18)
    .orderByDesc(User::getId)
    .toList();

// 按主键查一条（逻辑删除自动过滤，查不到返回 null）
User user = LightQuery.queryById(User.class, 1L);
```

### 5.2 全部条件操作符

```java
LightQuery.queryable(User.class)
    // ── 比较（null → IS NULL，非 null → = / <> / > / >= / < / <=）
    .col(User::getName).eq("frank")
    .col(User::getName).ne("alice")
    .col(User::getAge).gt(18)
    .col(User::getAge).ge(18)
    .col(User::getAge).lt(60)
    .col(User::getAge).le(60)

    // ── IN / NOT IN（空集合安全：空 IN → 1=0，空 NOT IN → 1=1）
    .col(User::getName).in("frank", "alice")
    .col(User::getName).in(List.of("frank", "alice"))
    .col(User::getName).notIn(List.of("bob"))

    // ── BETWEEN（双闭区间）
    .col(User::getAge).between(18, 60)

    // ── LIKE（\ % _ 自动转义，两侧加 %）
    .col(User::getName).like("frank")          // → LIKE '%frank%'
    .col(User::getName).notLike("frank")
    .col(User::getName).startsWith("fra")      // → LIKE 'fra%'
    .col(User::getName).endsWith("ank")        // → LIKE '%ank'

    // ── IS NULL / IS NOT NULL
    .col(User::getRemark).isNull()
    .col(User::getRemark).isNotNull()

    .toList();
```

### 5.3 分组与逻辑组合

```java
// 条件平铺为 AND
// and(() -> ...)  加括号：AND ( ... )
// or(() -> ...)   加括号：OR ( ... )
// 组内 .or()      切换下一个条件的连接符

List<User> users = LightQuery.queryable(User.class)
    .col(User::getStatus).eq(Status.ACTIVE)
    .and(w -> w.col(User::getName).like("frank")
              .or().col(User::getAge).ge(18))          // AND (name LIKE '%frank%' OR age >= 18)
    .or(w -> w.col(User::getStatus).eq(Status.FROZEN)) // OR (status = 'FROZEN')
    .toList();
```

### 5.4 条件复用

`Consumer<Where<T>>` 可以缓存起来跨查询复用：

```java
// 定义可复用的过滤条件
Consumer<Where<User>> activeFilter = w -> w.col(User::getStatus).eq(Status.ACTIVE);

// 多处使用
long count = LightQuery.queryable(User.class).and(activeFilter).count();
List<User> rows = LightQuery.queryable(User.class)
    .and(activeFilter)
    .col(User::getAge).ge(18)
    .toList();
```

### 5.5 动态条件（按需过滤）

`.when(condition, block)` 只在 condition 为 true 时追加条件——典型的动态查询场景：

```java
// Web 请求参数：可能为 null（用户没填筛选条件）
String name = "frank";        // 可能是 null
Integer minAge = null;        // 可能是 null
Status status = Status.ACTIVE;

List<User> rows = LightQuery.queryable(User.class)
    .when(name != null,       q -> q.col(User::getName).like(name))
    .when(minAge != null,     q -> q.col(User::getAge).ge(minAge))
    .when(status != null,     q -> q.col(User::getStatus).eq(status))
    .toList();
// 只拼有值的条件：WHERE user_name LIKE '%frank%' AND status = 'ACTIVE'
```

`Where`（分组内）、`Updatable`、`Deletable`、`JoinOn` 上都有 `when()`。

### 5.5 排序

```java
// 单列
.orderByAsc(User::getCreatedAt)
.orderByDesc(User::getId)

// 多列（按声明顺序）
.orderByAsc(User::getStatus, User::getAge)

// 按聚合别名排序
.orderByDesc("total")
```

### 5.6 分页

两种方式，按场景选择：

```java
// ① offset 分页：实现简单，页深大时扫描成本高
PageResult<User> page = LightQuery.queryable(User.class)
    .orderByAsc(User::getId)
    .toPageResult(1, 20);              // 第 1 页，每页 20 条
// page.rows() → 当前页数据
// page.total() → 总条数
// page.pages() → 总页数

// ② seek 逻辑分页（keyset）：深页 O(1)，游标 = 上一页最后一行的排序值
List<User> page1 = LightQuery.queryable(User.class)
    .orderByAsc(User::getId)
    .limit(20)
    .toList();

Long lastId = page1.get(page1.size() - 1).getId();  // 上一页最后一条的 id
List<User> page2 = LightQuery.queryable(User.class)
    .orderByAsc(User::getId)
    .seekAfter(lastId)                 // 从 lastId 之后继续取
    .limit(20)
    .toList();
// 多列排序：seekAfter(v1, v2) 与排序列一一对应，支持混合方向
```

### 5.7 投影——只取需要的字段

```java
// ① 排除敏感字段：SELECT * 变为显式列清单（不含 balance 和 remark）
List<User> rows = LightQuery.queryable(User.class)
    .exclude(User::getBalance, User::getRemark)
    .toList();

// ② Tuple：标签 = 列名或别名
List<Tuple> rows = LightQuery.queryable(Order.class)
    .select(Order::getUserId, Aggregations.count().as("cnt"))
    .groupBy(Order::getUserId)
    .toTupleList();
// rows.get(0).get("user_id")     → 原始值
// rows.get(0).get("cnt", Long.class) → 按类型取
// rows.get(0).labels()           → 所有可用标签

// ③ record / VO 投影：组件名与标签按名字匹配（忽略大小写和下划线）
record UserSummary(String userName, Integer age) {}
List<UserSummary> summaries = LightQuery.queryable(User.class)
    .select(User::getName, User::getAge)
    .toList(UserSummary.class);
```

### 5.8 聚合与分组

```java
// 标量聚合终端
Number total = LightQuery.queryable(Order.class).sum(Order::getAmount);    // BigDecimal，空表=0
Number max = LightQuery.queryable(User.class).max(User::getAge);           // 驱动原生类型
Number avg = LightQuery.queryable(Order.class).avg(Order::getAmount);      // 空表=null
Number min = LightQuery.queryable(Order.class).min(Order::getAmount);

// 分组聚合
record OrderStat(Long userId, Long orderCount, BigDecimal totalAmount) {}
List<OrderStat> stats = LightQuery.queryable(Order.class)
    .select(Order::getUserId)
    .select(Aggregations.count().as("orderCount"),
            Aggregations.sum(Order::getAmount).as("totalAmount"))
    .groupBy(Order::getUserId)
    .having(w -> w.gt(Aggregations.count(), 2))
    .orderByDesc("orderCount")
    .toList(OrderStat.class);
```

### 5.9 join

```java
// INNER JOIN：两个 order 匹配同一个 user → user 出现两次（标准 SQL 行为）
List<Tuple> rows = LightQuery.queryable(User.class)
    .innerJoin(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
    .col(User::getName).like("f")
    .select(User::getName, Order::getAmount)
    .toTupleList();

// LEFT JOIN：左表全保留
List<Tuple> rows = LightQuery.queryable(User.class)
    .leftJoin(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
    .isNull(Order::getId)          // 没有订单的用户
    .select(User::getName)
    .toTupleList();

// RIGHT JOIN
LightQuery.queryable(User.class)
    .rightJoin(Order.class, on -> on.col(User::getId).eqColumn(Order::getUserId))
    ...
```

### 5.10 子查询

```java
// IN 子查询
List<User> bigSpenders = LightQuery.queryable(User.class)
    .col(User::getId).in(
        LightQuery.queryable(Order.class)
            .col(Order::getAmount).gt(100)
            .select(Order::getUserId))
    .toList();

// EXISTS 子查询（相关子查询：直接引用外层 lambda）
List<User> usersWithOrders = LightQuery.queryable(User.class)
    .whereExists(
        LightQuery.queryable(Order.class)
            .col(Order::getUserId).eqColumn(User::getId))
    .toList();

// 标量子查询
List<User> users = LightQuery.queryable(User.class)
    .col(User::getAge).gtSubQuery(
        LightQuery.queryable(User.class).select(Aggregations.avg(User::getAge)))
    .toList();
```

### 5.11 自连接

同一实体出现多次时，用 `QueryTable` 命名每次出现：

```java
QueryTable<Employee> staff = QueryTable.of(Employee.class, "staff");
QueryTable<Employee> manager = QueryTable.of(Employee.class, "mgr");

// 查每个员工及其经理姓名
List<Tuple> pairs = LightQuery.queryable(staff)
    .leftJoin(manager, on -> on.col(staff.col(Employee::getManagerId))
                              .eqColumn(manager.col(Employee::getId)))
    .col(staff.col(Employee::getName)).isNotNull()
    .select(staff.col(Employee::getName).as("employee_name"),
            manager.col(Employee::getName).as("manager_name"))
    .toTupleList();
```

### 5.12 动态表名（分表）

```java
LightQuery.queryable(User.class)
    .asTable("t_user_202401")   // 替换物理表名
    .toList();
```

---

## 6. 写入

### 6.1 插入

```java
// 单条插入（IDENTITY 主键自动回填）
User user = new User();
user.setName("frank");
user.setStatus(Status.ACTIVE);
LightQuery.insert(user);
System.out.println(user.getId());  // 自增主键已回填

// 批量插入（JDBC batch，IDENTITY 主键不回填）
List<User> users = List.of(user1, user2, user3);
LightQuery.insertBatch(users);

// 分批插入（每 500 条一批提交，适合大数据量）
LightQuery.insertBatch(users, 500);
```

### 6.2 更新

```java
// 全量更新：所有列都 SET（null 列写 NULL）
User user = LightQuery.queryById(User.class, 1L);
user.setName("new-name");
LightQuery.update(user);

// 选择性更新：只更新非 null 字段
user.setRemark("updated");
LightQuery.updateSelective(user);  // 只 SET remark = ?（其他字段不动）

// 链式更新（不需要先查出来）
int rows = LightQuery.updatable(User.class)
    .col(User::getStatus).set(Status.FROZEN)
    .col(User::getAge).setIncrement(1)     // age = age + 1
    .col(User::getRemark).setNull()         // remark = NULL
    .col(User::getId).eq(5L)
    .execute();                             // 返回影响行数

// 无条件更新需要显式确认（防全表事故）
LightQuery.updatable(User.class)
    .allowFullTable()
    .col(User::getStatus).set(Status.ACTIVE)
    .execute();
```

### 6.3 删除

```java
// 按主键删除（有 @LogicDelete → UPDATE deleted=1）
LightQuery.delete(user);

// 按主键值删除（幂等：0 行返回 0 不抛异常）
LightQuery.deleteById(User.class, 1L);

// 链式删除（有条件才执行）
int rows = LightQuery.deletable(User.class)
    .col(User::getStatus).eq(Status.FROZEN)
    .execute();

// 物理删除（跳过逻辑删除，直接 DELETE）
LightQuery.deletable(User.class)
    .physical()
    .col(User::getId).eq(1L)
    .execute();

// 无条件删除需要显式确认
LightQuery.deletable(User.class)
    .allowFullTable()
    .execute();
```

---

## 7. 事务

```java
Long orderId = LightQuery.inTransaction(tx -> {
    // tx 是 LightQuerySession，绑定事务连接——事务内的每个语句都用它
    tx.insert(order);

    tx.updatable(Account.class)
      .col(Account::getBalance).setIncrement(-amount)
      .col(Account::getId).eq(accountId)
      .execute();                       // 影响行数≠1 → 异常 → 自动回滚

    return order.getId();
});  // 正常返回 → commit；异常 → rollback 并原样抛出
```

嵌套事务复用外层连接（不支持 savepoint 语义）：

```java
LightQuery.inTransaction(tx1 -> {
    tx1.insert(a);
    LightQuery.inTransaction(tx2 -> {   // tx2 复用 tx1 的连接
        tx2.insert(b);
        return null;
    });
    return null;
});
```

---

## 8. 乐观锁（@Version）

```java
@Table(name = "t_doc")
public class Doc {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Version                        // 数值版本（int/Integer/long/Long）
    private Integer version;
}
```

```java
// UPDATE 自动追加版本校验：WHERE id = ? AND version = ?，SET version = version + 1
Doc doc = LightQuery.queryById(Doc.class, 1L);
doc.setTitle("new title");
LightQuery.update(doc);              // 成功 → version 自动 +1 并回填实体

// 并发冲突 → jakarta.persistence.OptimisticLockException
// （另一个线程已把 version 改了，当前 UPDATE 影响 0 行 → 抛异常）
```

---

## 9. 逻辑删除

```java
// 实体加 @LogicDelete 注解
@LogicDelete
private Integer deleted;    // 0=正常 1=已删除
```

```java
// 查询自动过滤：SELECT ... WHERE ... AND deleted = 0
List<User> users = LightQuery.queryable(User.class).toList();

// delete → UPDATE deleted = 1（不是物理 DELETE）
LightQuery.delete(user);

// 物理删除
LightQuery.deletable(User.class).physical().col(User::getId).eq(1L).execute();

// 查已删除的数据
LightQuery.queryable(User.class).includeDeleted().toList();
```

---

## 10. 主键策略

```java
// ① IDENTITY 自增（MySQL / H2 / PG SERIAL / SQL Server）
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;

// ② SEQUENCE（PG / Oracle / H2）
@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq_gen")
@SequenceGenerator(name = "order_seq_gen", sequenceName = "order_seq")
private Long id;
// 插入前自动取 nextval 回填实体；MySQL 不支持（抛 SqlBuildException）
```

---

## 11. 字段自动填充（FillListener SPI）

```java
// 启动时注册
LightQuery.setFillListener(new FillListener() {
    public void onInsert(Object entity) {
        if (entity instanceof User u) {
            u.setCreatedAt(LocalDateTime.now());
        }
    }
    public void onUpdate(Object entity) {
        if (entity instanceof User u) {
            u.setUpdatedAt(LocalDateTime.now());
        }
    }
});
```

生效范围：`insert / insertBatch / update / updateSelective`（实体级操作）。
链式 `Updatable / Deletable` 和 `delete / deleteById` 不触发。

---

## 12. 类型转换（@Convert）

```java
// 自定义转换器：实体类型 ↔ 数据库类型
@Converter
public class MoneyConverter implements AttributeConverter<Money, String> {
    public String convertToDatabaseColumn(Money attribute) {
        return attribute == null ? null : attribute.toString();
    }
    public Money convertToEntityAttribute(String dbData) {
        return dbData == null ? null : Money.parse(dbData);
    }
}

// 实体字段标注
@Convert(converter = MoneyConverter.class)
private Money amount;

// 存取自动转换：insert 时 Money→String，query 时 String→Money
```

---

## 13. SQL 日志

```java
// 开发环境：打印每条 SQL
LightQuery.setSqlLogger(new SqlLogger() {
    public void beforeExecute(String sql, List<Object> params) {
        System.out.println("SQL: " + sql + " | params=" + params);
    }
});
```

---

## 14. 条件操作符速查表

| 方法 | SQL | 说明 |
|---|---|---|
| `.eq(v)` | `= ?` | null → `IS NULL` |
| `.ne(v)` | `<> ?` | null → `IS NOT NULL` |
| `.gt(v)` | `> ?` | |
| `.ge(v)` | `>= ?` | |
| `.lt(v)` | `< ?` | |
| `.le(v)` | `<= ?` | |
| `.in(c)` | `IN (?, ?, ...)` | 空集合 → `1 = 0` |
| `.notIn(c)` | `NOT IN (?, ...)` | 空集合 → `1 = 1` |
| `.between(lo, hi)` | `BETWEEN ? AND ?` | 双闭区间 |
| `.notBetween(lo, hi)` | `NOT BETWEEN ? AND ?` | |
| `.like(s)` | `LIKE ?` | 值转义 `\ % _`，两侧加 `%` |
| `.notLike(s)` | `NOT LIKE ?` | |
| `.startsWith(s)` | `LIKE ?` | 右侧 `%` |
| `.endsWith(s)` | `LIKE ?` | 左侧 `%` |
| `.isNull()` | `IS NULL` | |
| `.isNotNull()` | `IS NOT NULL` | |
| `.eqColumn(col)` | `= col` | 列对列 |
| `.gtColumn(col)` | `> col` | 列对列 |

---

## 15. 常见问题

**Q: `eq(User::getAge, "abc")` 为什么编译不过？**

值类型在编译期校验——`getAge` 返回 `Integer`，"abc" 是 `String`，类型不匹配直接编译失败。这是强类型校验的设计意图，把类型错误提前到 IDE。

**Q: 枚举忘了 `@Enumerated` 会怎样？**

JPA 默认 `ORDINAL`（存数字 0、1、2）。建议总是显式标注 `@Enumerated(EnumType.STRING)`。

**Q: 深分页怎么处理？**

用 seek 逻辑分页替代大 offset：

```java
// 页深越大 offset 越慢；seekAfter 走索引扫描，代价恒定
List<User> next = LightQuery.queryable(User.class)
    .orderByAsc(User::getId)
    .seekAfter(lastRowId)    // 上一页最后一行的 id
    .limit(20)
    .toList();
```

**Q: MySQL 能用 SEQUENCE 主键吗？**

不能。MySQL 没有序列，配置 SEQUENCE 后首次插入会抛 `SqlBuildException`。MySQL 用 IDENTITY。

**Q: 多数据源下如何指定方言？**

注册时自动探测；需要覆盖时用重载：

```java
LightQuery.datasource("report", dsReport, new OracleDialect());
```

**Q: 怎么调试生成的 SQL？**

```java
// 方式一：toSql() 打印 SQL 和参数（不执行，消费 queryable）
String sql = LightQuery.queryable(User.class).col(User::getName).eq("x").toSql();
System.out.println(sql);

// 方式二：注册全局 SqlLogger
LightQuery.setSqlLogger(new SqlLogger() {
    public void beforeExecute(String sql, List<Object> params) {
        System.out.println("SQL: " + sql + " | params=" + params);
    }
});
```

---

## 支持范围

- 数据库：MySQL / MariaDB / PostgreSQL / H2 / Oracle (12c+) / SQL Server (2012+)
- 主键：`@Id`（可复合）+ IDENTITY + SEQUENCE
- 类型：基础类型、BigDecimal/BigInteger、枚举、`java.util.Date` 与 java.time 全家桶
- 事务：编程式 `inTransaction` + Spring 事务对接
- 多数据源：静态门面内置
- 强类型：`col()` 编译期校验值类型
- 投影：Tuple / VO / record
- 分页：offset + seek (keyset)
- 乐观锁：`@Version`
- 逻辑删除：`@LogicDelete`
- 自动填充：`FillListener` SPI
- 类型转换：JPA `@Convert` / `AttributeConverter`
- SQL 日志：`SqlLogger` SPI
- upsert：MySQL / PG

## License

[Apache-2.0](LICENSE)
