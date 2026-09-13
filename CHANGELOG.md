# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.4.1] — 2026-09-13

### Added
- `LightQueryLoggers.slf4j()` / `slf4j(String)` / `slf4j(Logger)`: ready-made
  `SqlLogger` writing statements and timings at DEBUG and failures at ERROR
  (with the exception attached). `slf4j-api` is an **optional** dependency —
  zero runtime impact unless the adapter factory is invoked; when SLF4J is
  missing the factory fails with guidance. Test matrix T28.

### Fixed
- **upsert actually works on H2 now**: H2 2.x rejects both
  `ON CONFLICT .. DO UPDATE` and `ON DUPLICATE KEY UPDATE` in regular mode
  (probed against 2.2.224) — `H2Dialect` renders the native
  `MERGE INTO .. KEY ..` form. Previously the first real upsert execution
  would have failed with a syntax error.
- **upsert can now fire its conflict path for identity keys**: a non-null
  identity primary key joins the statement's column list so the
  duplicate-handling clause can match an existing row (previously identity
  keys were always omitted, making every upsert a fresh insert).
- A failing statement no longer masks its SQL error with a
  NullPointerException when any bind parameter is null
  (`DataAccessException` copied params via `List.copyOf`, which rejects null
  elements — null bind values are perfectly legal).
- `upsert` now triggers `FillListener` (`onInsert` + `onUpdate` when all
  primary-key values are non-null; degenerates to `insert` with only
  `onInsert` otherwise) — it previously bypassed auto-filling entirely.
- `union` / `unionAll` fail fast instead of rendering invalid SQL: partners
  carrying `orderBy / limit / offset / forUpdate` are rejected with guidance
  (ordering and paging belong on the root query of the compound), and
  column-count mismatches between root and partner are rejected eagerly with
  both counts.

## [0.4.0] — 2026-09-13

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
- `exclude()` (v0.3 roadmap): drop chosen properties from the projected
  column list (`SELECT *` becomes an explicit list without them); ignored
  after an explicit `select(...)`, works on joined queries too. Test matrix
  T24 (`ExcludeH2Test`).
- raw SQL escape hatches (v0.4 roadmap): `sqlHint` (rendered right after the
  `SELECT` keyword, e.g. MySQL/PolarDB optimizer hints), `selectRaw`,
  `whereRaw(sql, args...)` (verbatim fragment AND-combined into the WHERE
  tree, values `?`-bound), `groupByRaw` and `orderByRaw` (rendered verbatim,
  no ASC/DESC appended). Raw content is never escaped — documented as the
  developer's responsibility. Test matrix T26 (`RawSqlH2Test`).
- `union` / `unionAll` between `Queryable`s and session-level `saveOrUpdate`
  (insert when the primary key is null, update otherwise). Test matrix T27
  (`UnionAndSaveOrUpdateTest`).
- Upsert (v0.4 roadmap): `LightQuery.upsert(entity)` renders
  `ON DUPLICATE KEY UPDATE` (MySQL) / `ON CONFLICT DO UPDATE` (PG, H2);
  unsupported dialects fail with guidance. `insertBatch(entities, batchSize)`
  splits batches. Test matrix T23 (`BatchUpsertH2Test`).
- JPA `@Convert` / `AttributeConverter` support (v0.4 roadmap): converter
  applies transparently on write and read, including condition values.
  Test matrix T22 (`ConverterH2Test`).
- `SqlLogger` SPI (v0.4 roadmap): `LightQuery.setSqlLogger(...)` observes
  every statement — `beforeExecute(sql, params)`, `afterExecute(sql, ms)`,
  `onError(sql, params, exception)`. Test matrix T25 (`SqlLoggerH2Test`).

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
  `on.eq(colA, colB)`, `.set(col, v)`, ...). Call `col(...)` once to get a
  typed handle — the property value type is pinned at creation, so every
  condition built from it is checked at compile time: equality family
  (`eq/ne/in/notIn/isNull/isNotNull`, same-type `eqColumn/neColumn`,
  sub-queries), range (`gt/ge/lt/le/between/notBetween` and same-type
  column-to-column variants), text (`like/notLike/startsWith/endsWith`,
  only meaningful on `String` columns), `set/setNull/setIncrement` on the
  `Updatable` handle (`setIncrement` only meaningful on numeric columns).
  Scalar terminals `sum/avg/max/min` require `Number` properties.
  `set(...)` on a joined-table column still fails fast at runtime.
  Migration table lives in README ("条件"). Test matrix T21
  (`StrongTypingTest`, positive run + documented must-not-compile cases).
- update join / delete join statement shape is now comma-style with ON
  merged into WHERE on every dialect (MySQL `UPDATE a t0, b t1 SET ..`,
  `DELETE t0 FROM a t0, b t1`; SQL Server
  `UPDATE t0 SET .. FROM a t0, b t1`; PostgreSQL unchanged
  `UPDATE .. FROM` / `DELETE .. USING`). `Dialect.JoinPieces` shrank
  accordingly (dropped `joinedTables`/`onConditions`).
- **Breaking: `when(boolean)` removed from all builders.** Dynamic conditions
  are expressed with boolean-first overloads on every condition method —
  `.col(User::getName).like(name != null, name)` — which keep the condition
  chained without wrapping lambdas.

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
