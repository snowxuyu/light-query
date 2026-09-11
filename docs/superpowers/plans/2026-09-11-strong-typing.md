# Strong Typing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将封装 API 全链路强类型化并保持全量测试变绿，达到生产级可用。

**Architecture:** `TypedColumn`/`UpdatableColumn` 由终类拆为三层继承（基类 equality → Comparable → String，Number 独立分支挂 Comparable 下）；各 Builder 用不同名入口（`col/cmpCol/strCol/numCol`）避开泛型擦除；`having/聚合/seekAfter/pk` 收紧签名；测试整体迁移。

**Tech Stack:** Java 17, Maven, JUnit 5, H2, Jakarta Persistence annotations.

## Global Constraints

- 允许破坏性变更；编译期严格分层卡死；全链路收紧。
- 行为零变更：不改 SQL 渲染与 `ColumnMeta.toDbValue` 转换路径。
- 快照零 diff：`SqlSnapshotTest`/`DialectShapeTest` 的 SQL 字符串不变。
- 全量测试绿为放行条件；`main` 分支为 PR 目标（本次只改 `dev/0.3.0` 工作区，不主动推 PR）。

---

### Task 1: 分层 Handle 基建

**Files:**
- Modify: `light-query/src/main/java/com/lightquery/query/TypedColumn.java`
- Create: `light-query/src/main/java/com/lightquery/query/ComparableColumn.java`
- Create: `light-query/src/main/java/com/lightquery/query/StringColumn.java`
- Create: `light-query/src/main/java/com/lightquery/query/NumberColumn.java`
- Modify: `light-query/src/main/java/com/lightquery/query/UpdatableColumn.java`
- Create: `light-query/src/main/java/com/lightquery/query/UpdatableComparableColumn.java`
- Create: `light-query/src/main/java/com/lightquery/query/UpdatableStringColumn.java`
- Create: `light-query/src/main/java/com/lightquery/query/UpdatableNumberColumn.java`

**Interfaces:**
- Consumes: `ColumnResolver`, `ColumnRef`, `ColumnMeta`, `Condition`, `ConditionGroup`, `Operator`, `Escape`, `Queryable`（子查询模型 seam）。
- Produces: `ComparableColumn<B,V extends Comparable<V>> extends TypedColumn<B,V>`；`StringColumn<B> extends ComparableColumn<B,String>`；`NumberColumn<B,V extends Number & Comparable<V>> extends ComparableColumn<B,V>`；Updatable 侧镜像三层。Builder 入口返回这些类型（Task 2 消费）。

- [ ] **Step 1: TypedColumn 去 final、开放 protected**

将 `public final class TypedColumn` 改为 `public class TypedColumn`，字段与构造器改为 `protected`，`add/converted/compareColumn/subQueryCondition` 改为 `protected`，删除 `like/notLike/startsWith/endsWith/gt/ge/lt/le/between/notBetween/gtColumn/geColumn/ltColumn/leColumn`（移入子类），保留 `eq/ne/in/notIn/isNull/isNotNull/eqColumn/neColumn/子查询系列`。

- [ ] **Step 2: 创建 Comparable/String/NumberColumn**

```java
package com.lightquery.query;
// ComparableColumn: gt/ge/lt/le/between/notBetween + gtColumn/geColumn/ltColumn/leColumn
public class ComparableColumn<B, V extends Comparable<V>> extends TypedColumn<B, V> {
    protected ComparableColumn(B b, ConditionGroup g, ColumnRef r, ColumnMeta m, ColumnResolver res) { super(b, g, r, m, res); }
    public B gt(V v) { return add(Operator.GT, v); }
    // ... ge/lt/le/between/notBetween/gtColumn/...
}
// StringColumn extends ComparableColumn<B,String>: like/notLike/startsWith/endsWith
// NumberColumn<B,V extends Number & Comparable<V>> extends ComparableColumn<B,V>: 空壳（聚合/setIncrement 语义标记，Updatable 侧 Number 层才有 setIncrement）
```

- [ ] **Step 3: UpdatableColumn 同理拆分**（`set/setNull/eq/ne/in/.../eqColumn` 留基类；比较移入 `UpdatableComparableColumn`；`like` 进 `UpdatableStringColumn`；`setIncrement` 仅 `UpdatableNumberColumn`）。

- [ ] **Step 4: 编译基建**

Run: `mvn -q -pl light-query -am compile -DskipTests`
Expected: BUILD SUCCESS（Builder 还没接新入口，老测试此时会因缺方法而失败是正常的，下一步处理）。

- [ ] **Step 5: Commit**

```bash
git add light-query/src/main/java/com/lightquery/query/
git commit -m "feat: layered strongly-typed column handles"
```

### Task 2: Builder 入口拆分 + JoinOn 删除清单

**Files:**
- Modify: `Queryable.java`, `Where.java`, `JoinOn.java`, `Updatable.java`, `Deletable.java`

**Interfaces:**
- Consumes: Task 1 的四层 Handle。
- Produces: 每个 Builder 的 `col/cmpCol/strCol/numCol` ×（lambda + TableColumn）入口；`JoinOn` 无通配符 `eq(colA,colB)`。

- [ ] **Step 1: 每个 Builder 加 8 个入口**

```java
public <C, V> TypedColumn<Queryable<T>, V> col(SFunction<C, V> c) { ... } // 保留
public <C, V extends Comparable<V>> ComparableColumn<Queryable<T>, V> cmpCol(SFunction<C, V> c) { ... }
public <C> StringColumn<Queryable<T>> strCol(SFunction<C, String> c) { ... }
public <C, V extends Number & Comparable<V>> NumberColumn<Queryable<T>, V> numCol(SFunction<C, V> c) { ... }
// TableColumn 版同理：col(TableColumn<?,V>) / cmpCol(TableColumn<?,V extends Comparable<V>>) / strCol(TableColumn<?,String>) / numCol(...)
```

`Where/JoinOn/Updatable/Deletable` 逐个复制（把 `Queryable<T>` 换成自身泛型；`Updatable` 返回 Updatable 三层）。

- [ ] **Step 2: JoinOn 删除通配符老重载**

删除 `eq/ne/gt/ge/lt/le(SFunction, SFunction)` 六个方法，保留 `eq(SFunction<C,V>, V value)` 常量家族、`col/cmpCol/strCol`、`eqColumn(TableColumn,TableColumn同V)` 系列。

- [ ] **Step 3: 编译**

Run: `mvn -q -pl light-query -am compile -DskipTests`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add light-query/src/main/java/com/lightquery/query/
git commit -m "feat: layered col entry points, drop JoinOn wildcards"
```

### Task 3: 全链路收紧点

**Files:**
- Modify: `Where.java`（having 值 `Object`→`Number`）、`Queryable.java`（`sum/avg/max/min` 入参 `<C,V extends Number>`、`seekAfter(Comparable<?>...)`）、`Aggregations.java`（`sum/avg/max/min` 入参 `<C,V extends Number>`）、`LightQuerySession.java` + `LightQuery.java`（单主键 `<T,P>` 重载）、子查询 Queryable 侧 `<V>` 收紧。

- [ ] **Step 1: having 聚合值收紧为 Number**

```java
public Where<T> gt(Aggregate aggregate, Number value) { return addAggregate(aggregate, Operator.GT, value); }
// eq/ne/gt/ge/lt/le 六个全改
```

- [ ] **Step 2: 聚合函数收紧**

```java
// Aggregations
public static <C, V extends Number> Aggregate sum(SFunction<C, V> col) { ... } // avg/max/min 同理
// Queryable
public <C, V extends Number> Number sum(SFunction<C, V> col) { ... } // avg/max/min 同理
```

- [ ] **Step 3: seekAfter + pk**

```java
public Queryable<T> seekAfter(Comparable<?>... values) { ... } // 内部计数/空值检查保留，values[i] 类型改为 Comparable
// LightQuerySession + LightQuery
public <T, P> T queryById(Class<T> entityType, P pkValue) { return queryById(entityType, new Object[]{pkValue}); }
public <P> int deleteById(Class<?> entityType, P pkValue) { return deleteById(entityType, new Object[]{pkValue}); }
```

- [ ] **Step 4: 编译**

Run: `mvn -q -pl light-query -am compile -DskipTests`
Expected: BUILD SUCCESS（测试仍红，下一步迁移）。

- [ ] **Step 5: Commit**

```bash
git add light-query/src/main/java/
git commit -m "feat: tighten having, aggregations, seekAfter and pk signatures"
```

### Task 4: 测试迁移 + StrongTypingTest + 全量绿

**Files:**
- Modify: `QueryH2Test, SqlSnapshotTest, DialectShapeTest, JoinSubqueryH2Test, SelfJoinH2Test, LogicDeleteH2Test, SafetyTest, CrudH2Test, SeekPaginationH2Test, UpdateJoinTest`（`col→strCol/cmpCol/numCol`，`on.eq(a,b)→on.cmpCol(a).eqColumn(b)` 或 `on.col(a).eqColumn(b)`，`seekAfter((Object) null)→seekAfter((Comparable<?>) null)`）。
- Create: `light-query/src/test/java/com/lightquery/StrongTypingTest.java`

**Interfaces:**
- Consumes: Task 1–3 的全部 API。
- Produces: 全量测试绿；SQL 快照零 diff。

- [ ] **Step 1: 批量迁移测试调用点**

```bash
# 示例（逐文件核对，不可盲跑）：
grep -rn "\.col(" light-query/src/test/java/ | grep -E "like|startsWith|endsWith"   # → strCol
grep -rn "\.col(" light-query/src/test/java/ | grep -E "between|\.gt\(|\.ge\(|\.lt\(|\.le\("  # → cmpCol
grep -rn "setIncrement" light-query/src/test/java/  # → numCol
```

- [ ] **Step 2: 新增 StrongTypingTest（H2 正例）**

```java
package com.lightquery;
class StrongTypingTest {
    // strCol.like/startsWith、cmpCol.ge/between、numCol.setIncrement、sum(Number列)、having(count, Number) 全走通；
    // 反例只放文档注释（javac 期望错误），不做负向编译测试。
}
```

- [ ] **Step 3: 全量测试**

Run: `mvn -q -pl light-query -am test`
Expected: Tests run 全部 PASS，无 ERROR/FAIL。

- [ ] **Step 4: Commit**

```bash
git add light-query/src/test/
git commit -m "test: migrate to strongly-typed cols, add StrongTypingTest"
```

### Task 5: 文档与收尾

**Files:**
- Modify: `README.md`, `docs/DESIGN.md`, `CHANGELOG.md`（迁移表 + breaking change 记录）。

- [ ] **Step 1: 补迁移表与 javadoc 指向**
- [ ] **Step 2: 全量复验** `mvn -q -pl light-query -am test` 全绿
- [ ] **Step 3: Commit** `git commit -m "docs: strong typing migration notes"`
