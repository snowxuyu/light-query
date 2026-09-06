# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
