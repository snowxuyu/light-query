# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- VO/record projection (v0.3 roadmap): `toList(Class<R>)` and
  `toPageResult(pageNo, pageSize, Class<R>)` map projected rows onto
  Java records (canonical constructor) or VO classes (setters). Components
  are matched to result-set labels by name ignoring case and underscores;
  values are coerced to the target types; plans are cached per type.
  Test matrix T17 (`ProjectionH2Test`).
- Keyset (seek) pagination (v0.3 roadmap): `seekAfter(values...)` adds the
  predicate matching everything after the given sort-key values, honouring
  per-column sort direction and composing with user conditions. Rejects
  missing orderBy, mismatched value counts and null values. Test matrix T18
  (`SeekPaginationH2Test`).
- update join / delete join (v0.3 roadmap): `Updatable.join(...)` and
  `Deletable.join(...)` join other tables for filtering. Statement shape is
  dialect-driven (MySQL `UPDATE a JOIN b SET ..` / `DELETE t0 FROM ..`,
  PostgreSQL `UPDATE .. FROM` / `DELETE .. USING`, SQL Server
  `UPDATE alias SET .. FROM ..`); Oracle and H2 reject it with guidance.
  `set(...)` stays limited to the updated entity; logic delete becomes an
  UPDATE join. `toSql()` added to both builders for debugging. Test matrix
  T20 (`UpdateJoinTest`).
- Oracle (12c+) and SQL Server (2012+) dialects (v0.3 roadmap): quoting,
  OFFSET/FETCH pagination (neutral ORDER BY for SQL Server), LIKE ESCAPE and
  SEQUENCE support; JDBC URL auto-detection for both. Test matrix T19
  (`DialectShapeTest`).

### Fixed
- `Tuple` no longer fails with NullPointerException when a projected column
  value is SQL NULL (regression introduced with the Map-free constructor).
- Sub-query conditions no longer drop table aliases: any `in(subQuery)` /
  scalar-subquery condition marks the model so the outer query renders `t0`
  aliases (previously `SqlSnapshotTest.inSubQuery` produced unaliased SQL).

### Changed
- **Breaking: conditions are now strongly typed.** `Queryable` / `Where` /
  `JoinOn` / `Updatable` / `Deletable` no longer expose bare two-argument
  conditions (`.eq(col, value)`, `.gt(col, value)`, `.like(col, s)`,
  `on.eq(colA, colB)`, `.set(col, v)`, ...). Pick a layered entry first —
  the property type is checked at compile time:
  - `col(...)`: equality family (`eq/ne/in/notIn/isNull/isNotNull`,
    same-type `eqColumn/neColumn`, sub-queries);
  - `cmpCol(...)`: only `Comparable` properties; adds
    `gt/ge/lt/le/between/notBetween`;
  - `strCol(...)`: only `String` properties; adds
    `like/notLike/startsWith/endsWith`;
  - `numCol(...)`: only `Number` properties; scalar terminals
    `sum/avg/max/min` require `Number` properties too;
  - `Updatable` mirrors the layers (`UpdatableColumn` family) with
    `set/setNull` on `col`, `setIncrement` only on `numCol`;
    `set(...)` on a joined-table column still fails fast at runtime.
  Migration table lives in README ("条件"). Test matrix T21
  (`StrongTypingTest`, positive run + documented must-not-compile cases).
- update join / delete join statement shape is now comma-style with ON
  merged into WHERE on every dialect (MySQL `UPDATE a t0, b t1 SET ..`,
  `DELETE t0 FROM a t0, b t1`; SQL Server
  `UPDATE t0 SET .. FROM a t0, b t1`; PostgreSQL unchanged
  `UPDATE .. FROM` / `DELETE .. USING`). `Dialect.JoinPieces` shrank
  accordingly (dropped `joinedTables`/`onConditions`).

## [0.2.0] — 2026-09-06

### Added
- Multi-datasource support through the static facade: `LightQuery.primary(ds)`
  registers the primary data source (the default target of every static call);
  `LightQuery.datasource(name, ds)` registers named ones with get-or-register
  semantics (same name + same DataSource resolve to the same cached session).
  Call sites name the datasource explicitly:
  `LightQuery.datasource("order", dsOrder).queryable(...)` or
  `LightQuery.datasource("order").queryable(...)`. Switching is always
  explicit — no automatic routing. With a single data source nothing needs to
  be specified: `LightQuery.queryable(...)` targets the primary. Dialects are
  detected per data source at first registration (explicit-dialect overloads
  available); `LightQuery.reset()` clears the registry for tests. Test matrix
  T11 (`MultiDataSourceH2Test`).
- `@Version` optimistic locking (v0.2 roadmap): numeric versions
  (int/Integer/long/Long) participate in entity-level `update` /
  `updateSelective` / `delete` — the WHERE gains the expected version and the
  SET gains an increment; a conflict raises
  `jakarta.persistence.OptimisticLockException` and the new version is
  back-filled into the entity. Inserts initialise a null version to 0.
  Fluent `Updatable`/`Deletable` and `deleteById` do not participate
  automatically. Test matrix T12 (`OptimisticLockH2Test`).
- SEQUENCE key generation (v0.2 roadmap): `@GeneratedValue(strategy=SEQUENCE)`
  with a `@SequenceGenerator` fetches `nextval` per entity before the INSERT
  (single and batch); supported by the PostgreSQL and H2 dialects, rejected on
  MySQL. Test matrix T13 (`SequenceH2Test`).
- Self-joins via `QueryTable.of(entity, alias)` and occurrence-bound
  `TableColumn`s (v0.2 roadmap): join the same entity several times under
  explicit aliases, address columns with `table.col(Entity::getProperty)`;
  plain lambdas on a duplicated entity fail with guidance. Test matrix T14
  (`SelfJoinH2Test`).
- `FillListener` SPI for entity field auto-filling (v0.2 roadmap): global
  `LightQuery.setFillListener(...)` callbacks fire before the SQL of
  `insert` / `insertBatch` / `update` / `updateSelective` is built. Test
  matrix T15 (`FillListenerH2Test`).

### Changed
- **Two-module build (0.2.0-SNAPSHOT):** the repository is now an aggregator
  (`light-query-parent`) with the core in `light-query/` and a new
  `light-query-spring-boot-starter/` module (Boot 3.x auto-configuration,
  `SpringConnectionProvider` for Spring-managed transactions). Dependency
  coordinates of the core artifact are unchanged. Test matrix T16
  (`SpringStarterTest`).
- **Static facade split:** `LightQuery` is now a stateless static entry point —
  register once at startup, then call `LightQuery.queryable(...)` /
  `LightQuery.insert(...)` / `LightQuery.inTransaction(...)` directly. The
  former immutable instance class moved to `LightQuerySession` (returned by
  `datasource(...)`/`of(...)`, handed to `inTransaction` work); the earlier
  `builder()` bootstrap and instance-level `of(String)` switching are replaced
  by the facade registry.
- **Breaking renames for naming-standard compliance (Alibaba Java Coding
  Guidelines — no opaque abbreviations):** aggregate factory `F` →
  `Aggregations`; aggregate handle `Agg` → `Aggregate` (record components
  `func`/`fn` → `function`/`property`); operator enum `Op` → `Operator`
  (`Condition.getOp()` → `getOperator()`). Internal identifiers renamed
  accordingly: `Expr.func/arg` → `Expr.function/argument`, `Ctx` →
  `RenderContext`, `resolveAgg` → `resolveAggregate`.
- `Tuple` no longer takes a `Map` in its constructor — maps are kept out of
  the public API surface. `new Tuple(labels, values)` builds the label index
  internally; duplicate labels keep the first occurrence (same semantics as
  before).

## [0.1.0] — first public milestone

### Added
- Entity mapping on standard Jakarta Persistence annotations
  (`@Table`, `@Column`, `@Id`, `@GeneratedValue(IDENTITY)`, `@Transient`,
  `@Enumerated`); the only custom annotation is `@LogicDelete`.
- Runtime lambda-to-column resolution (`SFunction` + `SerializedLambda`),
  no annotation processor required.
- Fluent single-table queries: all comparison operators, `and`/`or` nesting,
  `like/startsWith/endsWith` with escaping, IN/NOT IN (empty-safe),
  BETWEEN, IS NULL, ORDER BY, LIMIT/OFFSET, DISTINCT, `FOR UPDATE`,
  dynamic table name (`asTable`).
- Joins (`innerJoin`/`leftJoin`/`rightJoin`) with automatic table aliasing
  (t0, t1, …) and lambda-based ON conditions.
- Sub-queries: IN / NOT IN, scalar comparisons (`eqSubQuery` …),
  correlated `whereExists` / `whereNotExists` via `eqColumn`.
- Aggregations: `F.count/sum/avg/max/min`, GROUP BY / HAVING, Tuple
  projection, scalar aggregate terminals.
- Offset pagination with totals (`toPageResult`) and `PageResult`.
- Entity CRUD: insert with identity back-fill, JDBC batch insert,
  update / updateSelective by primary key with affected-row verification,
  logic-delete aware delete, `queryById` / `deleteById`.
- Fluent `Updatable` / `Deletable` builders with full-table protection
  (`allowFullTable()` escape hatch) and `setIncrement`.
- Programmatic transactions (`inTransaction`) with rollback-on-exception
  and nested-transaction reuse.
- Dialects: MySQL / MariaDB, PostgreSQL, H2; dialect auto-detection from
  the JDBC URL.
- ConnectionProvider SPI as the single integration seam for containers and
  (future) Spring transaction managers.
- Test matrix T1–T10 (82 tests): metadata, lambda resolution, SQL snapshots
  for MySQL and PostgreSQL, H2 behavioural integration tests for CRUD /
  queries / joins / logic delete / transactions, misuse & injection safety,
  concurrency smoke.
