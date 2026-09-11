# Strong Typing API Design — light-query

Date: 2026-09-11
Status: approved (sections 1-4 confirmed by user)
Goals: 梳理整体项目 + 封装 API 强类型校验 + 生产级可用、没有 bug
Constraints: 允许破坏性变更；编译期严格分层卡死；全链路收紧；行为零变更（SQL 渲染不动）

## 0. 项目梳理（现状）

- 链路：`LightQuery`（静态门面）→ `LightQuerySession`（单数据源会话）→
  `Queryable` / `Updatable` / `Deletable`（三大 Builder）→
  `Where` / `JoinOn` / `TypedColumn` / `UpdatableColumn`（条件收集）→
  `QueryModel` / `SqlBuilder` / `Dialect`（模型+方言渲染）→
  `JdbcExecutor` / `EntityOperations`（执行）。
- 工作区已有未提交改造：`eq(col, value)` 弱类型已切到 `col(col).eq(value)`，
  新增 `TypedColumn` / `UpdatableColumn`，测试已同步改名。
- 残留弱类型缺口（本设计覆盖）：
  1. `TypedColumn`/`UpdatableColumn` 的 `like/gt/between/setIncrement` 不限 `V`；
  2. `JoinOn` 残留 `eq(colA, colB)` 通配符老重载（两边类型可不一致）；
  3. `select/groupBy/orderBy/sum/max/min/subquery/seekAfter/pkValues` 通配符 + `Object`；
  4. `Where.having` 聚合比较值是 `Object`。

## 1. 分层 Handle 体系（已确认）

- `TypedColumn<B,V>`（基类）：只留 `eq/ne/in/notIn/isNull/isNotNull/eqColumn/neColumn` + 子查询系列；
  `like/gt/between/setIncrement` 全部移出。
- `ComparableColumn<B,V extends Comparable<V>> extends TypedColumn`：
  `+ gt/ge/lt/le/between/notBetween + gtColumn/geColumn/ltColumn/leColumn`。
  覆盖 `Integer/Long/BigDecimal/LocalDateTime/Enum/String`。
- `StringColumn<B> extends ComparableColumn<B,String>`：
  `+ like/notLike/startsWith/endsWith(String)`。
- `NumberColumn<B,V extends Number & Comparable<V>> extends ComparableColumn`：
  聚合 / `setIncrement` 专用。
- `Updatable` 侧镜像：`UpdatableColumn` 基类（`set/setNull/eq/ne/in...`）+
  `UpdatableComparable/UpdatableString/UpdatableNumber`
 （`setIncrement` 仅 Number 层，`like` 仅 String 层）。
- 继承而非组合：子类 is-a 基类，`col(x).eq(y)` 仍可编译，只有错用才编译失败。

## 2. Builder 入口拆分 + 删除清单（已确认）

- 擦除冲突原因：`col(SFunction<C,String>)` 与 `col(SFunction<C,V>)` 同擦除，
  不能靠泛型 bound 重载，必须拆方法名。
- 统一入口：`col()`（基类，任意 `V`）、`cmpCol()`（`V extends Comparable`）、
  `strCol()`（`String`）、`numCol()`（`V extends Number`，聚合/自增专用）。
  各 Builder（`Queryable/Where/JoinOn/Updatable/Deletable`）统一提供；
  `TableColumn<?,V>` 同理拆 `col/cmpCol/strCol/numCol(TableColumn<?,V extends...>)`。
- 删除清单：`JoinOn` 的 `eq/ne/gt/ge/lt/le(colA,colB)` 通配符老重载全删；
  基类 `like/gt/between/setIncrement` 移入子层；
  `Queryable.in(col, Collection<?>)/eqSubQuery` 通配符收紧为与列同 `V`。
- 迁移表：`col→cmpCol`（`gt/ge/lt/between`）、`col→strCol`（`like/startsWith`）、
  `col→numCol`（`setIncrement/sum`）。

## 3. 全链路收紧点（已确认）

- `Where.having`：`gt/ge/lt/le/eq/ne(Aggregate, Object)` → 值收紧为 `Number`。
- `Queryable` 聚合：`sum/avg/max/min(SFunction<C,?>)` → `<C,V extends Number>`；
  `select/groupBy/orderBy(SFunction)` 只选列无值可卡，保持泛型不拆。
- 子查询：`Queryable` 侧收紧列与值同 `V`，标量单列类型一致性运行时由 DB 校验，
  不引入 `ScalarSubquery` 包装（控 scope）。
- `seekAfter(Object...)` → `seekAfter(Comparable<?>...)`，保留运行时数量/空值检查；
  `queryById/deleteById` 新增单主键 `<T,P>` 重载，复合主键保留 `Object...`。
- `Aggregations.sum/avg/max/min(SFunction<C,?>)` → `<C,V extends Number>`；
  `count/countDistinct` 保持。

## 4. 生产级质量门（已确认）

- 行为零变更：不改 SQL 渲染与 `ColumnMeta.toDbValue`；
  `SqlSnapshotTest/DialectShapeTest` 快照零 diff（调用点改名除外，SQL 字符串不变）。
- 验证矩阵：全量测试绿；新增 `StrongTypingTest`（正例编译+执行；
  反例以文档附期望 javac 错误，不引入编译期负向测试框架）。
- 迁移与文档：`README/DESIGN` 补迁移表；删除/改签名 API 加 javadoc 指向；
  `CHANGELOG` 记 breaking change。
- 回滚线：若枚举/`Boolean` 等常用类型走不通 `cmpCol` bound，
  则放宽该层 bound 为 `<V>` + 运行时 `Comparable` 检查，以全量测试绿为放行条件。

## 5. 备选方案（已否决，留档）

- B 单入口+运行时校验：改动最小，但违背编译期卡死要求。
- C 字段元模型（JOOQ 式 `User_.age`）：最严格但需代码生成，与 lambda 风格不兼容。

## 6. Spec 自检

- 无 TBD/TODO；第 1–4 节互洽（分层→入口→收紧点→质量门）；
- scope 为单轮实现（条件家族 + 形状 + 聚合 + 子查询签名 + pk/seek 签名），不含代码生成；
- 歧义已消除：`eqColumn/neColumn` 留基类；`select/orderBy` 不拆；子查询/`seekAfter`/复合主键保留运行时检查并已注明。
