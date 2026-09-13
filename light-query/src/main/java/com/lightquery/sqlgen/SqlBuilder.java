package com.lightquery.sqlgen;

import com.lightquery.exception.SqlBuildException;
import com.lightquery.query.model.ColumnRef;
import com.lightquery.query.model.Condition;
import com.lightquery.query.model.ConditionGroup;
import com.lightquery.query.model.Expr;
import com.lightquery.query.model.JoinSpec;
import com.lightquery.query.model.Operator;
import com.lightquery.query.model.OrderBy;
import com.lightquery.query.model.QueryModel;
import com.lightquery.query.model.Selectable;
import com.lightquery.query.model.TableRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the query model into parameterized SQL. This is the single place
 * where SQL text is produced; values are always bound as {@code ?}
 * placeholders, so generated statements are injection-safe by construction.
 */
public final class SqlBuilder {

    private SqlBuilder() {
    }

    // ------------------------------------------------------------------ query API

    public static SqlFragment select(QueryModel model, Dialect dialect) {
        return renderSelect(model, dialect, null, 0, false);
    }

    /**
     * COUNT over the model. Order/limit/forUpdate are ignored; a distinct or
     * grouped model is counted through a sub-query so row groups (not rows)
     * are counted.
     */
    public static SqlFragment count(QueryModel model, Dialect dialect) {
        boolean needsWrapper = model.isDistinct() || !model.getGroupBys().isEmpty();
        if (!needsWrapper) {
            RenderContext ctx = new RenderContext(dialect);
            Scope scope = Scope.root(model, 0);
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ")
                    .append(fromClause(model, scope, ctx, 0));
            sql.append(renderWhere(model.getWhere(), scope, ctx, 0));
            return ctx.toFragment(sql.toString());
        }
        SqlFragment inner = renderSelect(model, dialect, null, 0, true);
        String sql = "SELECT COUNT(*) FROM (" + inner.sql() + ") t";
        return new SqlFragment(sql, inner.params());
    }

    // ------------------------------------------------------------------ entity SQL API

    /** UPDATE for a single-table model (root table only, no aliases). */
    public static SqlFragment update(QueryModel model, List<SetClause> sets, Dialect dialect) {
        if (sets.isEmpty()) {
            throw new SqlBuildException("UPDATE requires at least one SET clause");
        }
        String table = model.getRoot().getTableName();
        RenderContext ctx = new RenderContext(dialect);
        Scope scope = Scope.root(model, 0);
        StringBuilder sql = new StringBuilder("UPDATE ").append(dialect.quote(table)).append(" SET ");
        for (int i = 0; i < sets.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            SetClause set = sets.get(i);
            sql.append(dialect.quote(set.column()));
            if (set.increment()) {
                sql.append(" = ").append(dialect.quote(set.column())).append(" + ?");
            } else {
                sql.append(" = ?");
            }
            ctx.params().add(set.value());
        }
        sql.append(renderWhere(model.getWhere(), scope, ctx, 0));
        return ctx.toFragment(sql.toString());
    }

    /** DELETE for a single-table model (root table only, no aliases). */
    public static SqlFragment delete(QueryModel model, Dialect dialect) {
        String table = model.getRoot().getTableName();
        RenderContext ctx = new RenderContext(dialect);
        Scope scope = Scope.root(model, 0);
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(dialect.quote(table));
        sql.append(renderWhere(model.getWhere(), scope, ctx, 0));
        return ctx.toFragment(sql.toString());
    }

    /**
     * UPDATE that joins other tables for filtering (multi-table syntax).
     * The model carries the joins; the statement shape is decided by the
     * dialect (MySQL: {@code UPDATE a JOIN b ON .. SET ..} — PostgreSQL:
     * {@code UPDATE a SET .. FROM b WHERE ..} — SQL Server:
     * {@code UPDATE t0 SET .. FROM a JOIN b ..}).
     */
    public static SqlFragment updateWithJoin(QueryModel model, List<SetClause> sets, Dialect dialect) {
        return updateOrDeleteWithJoin(model, sets, dialect, false);
    }

    /** DELETE that joins other tables for filtering (multi-table syntax). */
    public static SqlFragment deleteWithJoin(QueryModel model, Dialect dialect) {
        return updateOrDeleteWithJoin(model, List.of(), dialect, true);
    }

    private static SqlFragment updateOrDeleteWithJoin(QueryModel model, List<SetClause> sets,
                                                      Dialect dialect, boolean delete) {
        if (sets.isEmpty() && !delete) {
            throw new SqlBuildException("UPDATE requires at least one SET clause");
        }
        RenderContext ctx = new RenderContext(dialect);
        Scope scope = Scope.root(model, 0);
        String alias = dialect.quote(scope.aliasOf(model.getRoot()));
        String rootDefinition = dialect.quote(model.getRoot().getTableName()) + " " + alias;

        StringBuilder joinTableList = new StringBuilder();
        for (JoinSpec join : model.getJoins()) {
            String joinAlias = dialect.quote(scope.aliasOf(join.table()));
            if (joinTableList.length() > 0) {
                joinTableList.append(", ");
            }
            joinTableList.append(dialect.quote(join.table().getTableName())).append(' ').append(joinAlias);
        }

        StringBuilder qualifiedSets = new StringBuilder();
        StringBuilder bareSets = new StringBuilder();
        for (int i = 0; i < sets.size(); i++) {
            if (i > 0) {
                qualifiedSets.append(", ");
                bareSets.append(", ");
            }
            SetClause set = sets.get(i);
            String bareColumn = dialect.quote(set.column());
            String qualifiedColumn = alias + "." + bareColumn; // alias is already quoted
            if (set.increment()) {
                qualifiedSets.append(qualifiedColumn).append(" = ").append(qualifiedColumn).append(" + ?");
                bareSets.append(bareColumn).append(" = ").append(bareColumn).append(" + ?");
            } else {
                qualifiedSets.append(qualifiedColumn).append(" = ?");
                bareSets.append(bareColumn).append(" = ?");
            }
            ctx.params().add(set.value());
        }

        StringBuilder onConditions = new StringBuilder();
        for (JoinSpec join : model.getJoins()) {
            if (onConditions.length() > 0) {
                onConditions.append(" AND ");
            }
            onConditions.append(renderGroup(join.on(), scope, ctx, 0));
        }
        String whereClause = renderWhere(model.getWhere(), scope, ctx, 0).toString();
        if (onConditions.length() > 0) {
            // ON conditions are pre-merged into the WHERE clause so every
            // dialect assembles FROM/USING-style statements (see JoinPieces).
            whereClause = whereClause.isEmpty() ? " WHERE " + onConditions
                    : whereClause + " AND " + onConditions;
        }
        Dialect.JoinPieces pieces = new Dialect.JoinPieces(alias, rootDefinition,
                joinTableList.toString(),
                qualifiedSets.toString(), bareSets.toString(), whereClause);
        String sql = delete ? dialect.deleteJoinSql(pieces) : dialect.updateJoinSql(pieces);
        return ctx.toFragment(sql);
    }

    public static SqlFragment insert(String table, List<String> columns, List<Object> values, Dialect dialect) {
        if (columns.isEmpty()) {
            throw new SqlBuildException("INSERT requires at least one column");
        }
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(dialect.quote(table)).append(" (");
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
                placeholders.append(", ");
            }
            sql.append(dialect.quote(columns.get(i)));
            placeholders.append('?');
        }
        sql.append(") VALUES (").append(placeholders).append(')');
        return new SqlFragment(sql.toString(), values);
    }

    /** One SET entry of an UPDATE statement. */
    public record SetClause(String column, Object value, boolean increment) {
        public static SetClause of(String column, Object value) {
            return new SetClause(column, value, false);
        }

        public static SetClause increment(String column, long delta) {
            return new SetClause(column, delta, true);
        }
    }

    // ------------------------------------------------------------------ rendering

    private static SqlFragment renderSelect(QueryModel model, Dialect dialect, Scope parent,
                                            int depth, boolean stripForCount) {
        RenderContext ctx = new RenderContext(dialect);
        Scope scope = Scope.root(model, depth, parent);

        StringBuilder sql = new StringBuilder("SELECT ");
        if (model.isDistinct() && !model.isExistsProbe()) {
            sql.append("DISTINCT ");
        }
        if (model.isExistsProbe()) {
            sql.append("1");
        } else if (!model.getSelectExprs().isEmpty()) {
            appendSelectList(model, scope, ctx, depth, sql);
        } else if (model.getJoins().isEmpty() && model.getExcludedColumns().isEmpty()) {
            sql.append('*');
        } else {
            // joined query mapped to the root entity: select its columns explicitly
            // so result labels are unambiguous
            appendRootColumns(model, scope, ctx, sql);
        }

        sql.append(" FROM ").append(fromClause(model, scope, ctx, depth));
        sql.append(renderWhere(model.getWhere(), scope, ctx, depth));

        // UNION / UNION ALL: render partner SELECTs after the current one
        if (!model.getUnionPartners().isEmpty()) {
            sql.append(model.isUnionAll() ? " UNION ALL " : " UNION ");
            for (int u = 0; u < model.getUnionPartners().size(); u++) {
                QueryModel partner = model.getUnionPartners().get(u);
                if (u > 0) {
                    sql.append(model.isUnionAll() ? " UNION ALL " : " UNION ");
                }
                SqlFragment partnerFragment = renderSelect(partner, dialect, scope, depth + 1, stripForCount);
                sql.append(partnerFragment.sql());
                ctx.params().addAll(partnerFragment.params());
            }
        }

        if (!stripForCount) {
            if (!model.getGroupBys().isEmpty()) {
                sql.append(" GROUP BY ");
                appendJoined(sql, model.getGroupBys(), ", ", s -> renderSelectable(s, scope, ctx, depth, false));
                sql.append(renderHaving(model, scope, ctx, depth));
            }
            if (!model.getOrderBys().isEmpty()) {
                sql.append(" ORDER BY ");
                appendJoined(sql, model.getOrderBys(), ", ", order -> renderOrder(order, scope, ctx, depth));
            }
            if (model.isForUpdate()) {
                sql.append(" FOR UPDATE");
            }
            if (model.getLimit() != null) {
                long offset = model.getOffset() == null ? 0 : model.getOffset();
                return ctx.toFragment(dialect.page(sql.toString(), offset, model.getLimit()));
            }
        }
        return ctx.toFragment(sql.toString());
    }

    private static CharSequence fromClause(QueryModel model, Scope scope, RenderContext ctx, int depth) {
        StringBuilder sql = new StringBuilder(ctx.dialect().quote(model.getRoot().getTableName()));
        if (scope.aliasMode()) {
            sql.append(' ').append(ctx.dialect().quote(scope.aliasOf(model.getRoot())));
        }
        for (JoinSpec join : model.getJoins()) {
            sql.append(' ').append(join.type().text()).append(' ')
                    .append(ctx.dialect().quote(join.table().getTableName()));
            if (scope.aliasMode()) {
                sql.append(' ').append(ctx.dialect().quote(scope.aliasOf(join.table())));
            }
            sql.append(" ON ");
            if (join.on().isEmpty()) {
                throw new SqlBuildException("Join to " + join.table().getTableName()
                        + " has no ON condition — build it with the JoinOn consumer");
            }
            sql.append(renderGroup(join.on(), scope, ctx, depth));
        }
        return sql;
    }

    private static void appendSelectList(QueryModel model, Scope scope, RenderContext ctx,
                                         int depth, StringBuilder sql) {
        appendJoined(sql, model.getSelectExprs(), ", ", s -> renderSelectable(s, scope, ctx, depth, true));
    }

    private static void appendRootColumns(QueryModel model, Scope scope, RenderContext ctx,
                                          StringBuilder sql) {
        boolean aliasMode = scope.aliasMode();
        String alias = aliasMode ? ctx.dialect().quote(scope.aliasOf(model.getRoot())) : null;
        var columns = model.getRoot().getMeta().getColumns().stream()
                .filter(c -> !model.getExcludedColumns().contains(c.getColumnName()))
                .toList();
        appendJoined(sql, columns, ", ", column -> {
            String col = ctx.dialect().quote(column.getColumnName());
            return aliasMode ? alias + "." + col : col;
        });
    }

    private static CharSequence renderWhere(ConditionGroup where, Scope scope, RenderContext ctx, int depth) {
        String rendered = renderGroup(where, scope, ctx, depth);
        return rendered.isEmpty() ? "" : " WHERE " + rendered;
    }

    private static CharSequence renderHaving(QueryModel model, Scope scope, RenderContext ctx, int depth) {
        String rendered = renderGroup(model.getHaving(), scope, ctx, depth);
        return rendered.isEmpty() ? "" : " HAVING " + rendered;
    }

    private static CharSequence renderOrder(OrderBy order, Scope scope, RenderContext ctx, int depth) {
        String expression = switch (order) {
            case OrderBy.ByExpr e -> renderSelectable(e.expr(), scope, ctx, depth, false);
            case OrderBy.ByAlias a -> ctx.dialect().quote(a.alias());
        };
        return expression + (order.asc() ? " ASC" : " DESC");
    }

    // ------------------------------------------------------------------ conditions

    private static String renderGroup(ConditionGroup group, Scope scope, RenderContext ctx, int depth) {
        StringBuilder out = new StringBuilder();
        for (ConditionGroup.Node node : group.getChildren()) {
            String rendered = switch (node.child()) {
                case Condition condition -> renderCondition(condition, scope, ctx, depth);
                case ConditionGroup nested -> parenthesize(renderGroup(nested, scope, ctx, depth));
                default -> throw new SqlBuildException("Unknown condition child: "
                        + node.child().getClass().getName());
            };
            if (rendered.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(node.or() ? " OR " : " AND ");
            }
            out.append(rendered);
        }
        return out.toString();
    }

    private static String parenthesize(String sql) {
        return sql.isEmpty() ? "" : "(" + sql + ")";
    }

    private static String renderCondition(Condition condition, Scope scope, RenderContext ctx, int depth) {
        Operator operator = condition.getOperator();
        if (operator == Operator.EXISTS || operator == Operator.NOT_EXISTS) {
            return operator.text() + renderSubQuery(condition.getSubQuery(), scope, ctx, depth);
        }
        if (operator == Operator.RAW) {
            return renderRaw(condition, ctx);
        }
        String target = renderSelectable(condition.getTarget(), scope, ctx, depth, false);

        return switch (operator) {
            case IS_NULL, IS_NOT_NULL -> target + operator.text();
            case IN, NOT_IN -> target + operator.text() + renderInRightSide(condition, scope, ctx, depth);
            case BETWEEN, NOT_BETWEEN -> target + operator.text() + bindBoth(ctx, condition.getValues());
            case EQ, NE, GT, GE, LT, LE -> {
                if (condition.getSubQuery() != null) {
                    yield target + operator.text() + renderSubQuery(condition.getSubQuery(), scope, ctx, depth);
                }
                if (condition.getOther() != null) {
                    yield target + operator.text()
                            + renderSelectable(condition.getOther(), scope, ctx, depth, false);
                }
                List<Object> values = condition.getValues();
                Object value = values.isEmpty() ? null : values.get(0);
                if (value == null && operator == Operator.EQ) {
                    yield target + Operator.IS_NULL.text();
                }
                if (value == null && operator == Operator.NE) {
                    yield target + Operator.IS_NOT_NULL.text();
                }
                yield target + operator.text() + bindOne(ctx, value);
            }
            case LIKE, NOT_LIKE -> target + operator.text() + bindOne(ctx, condition.getValues().get(0))
                    + ctx.dialect().likeEscapeClause();
            case EXISTS, NOT_EXISTS, RAW -> throw new IllegalStateException("handled above");
        };
    }

    /**
     * Renders a {@code whereRaw} fragment verbatim (parenthesised, so it
     * concatenates safely with AND/OR) and binds its params in {@code ?}
     * order. The placeholder count must match the param count exactly.
     */
    private static String renderRaw(Condition condition, RenderContext ctx) {
        String fragment = condition.getRawSql();
        long marks = fragment.chars().filter(ch -> ch == '?').count();
        if (marks != condition.getValues().size()) {
            throw new SqlBuildException("whereRaw fragment has " + marks
                    + " '?' placeholders but " + condition.getValues().size() + " params");
        }
        ctx.params().addAll(condition.getValues());
        return "(" + fragment + ")";
    }

    private static String renderInRightSide(Condition condition, Scope scope, RenderContext ctx, int depth) {
        if (condition.getSubQuery() != null) {
            return renderSubQuery(condition.getSubQuery(), scope, ctx, depth);
        }
        List<Object> values = condition.getValues();
        if (values.isEmpty()) {
            // an empty IN matches nothing; an empty NOT IN matches everything
            return condition.getOperator() == Operator.IN ? "(1 = 0)" : "(1 = 1)";
        }
        StringBuilder placeholders = new StringBuilder("(");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                placeholders.append(", ");
            }
            placeholders.append('?');
            ctx.params().add(values.get(i));
        }
        return placeholders.append(')').toString();
    }

    private static String renderSubQuery(QueryModel sub, Scope scope, RenderContext ctx, int depth) {
        SqlFragment fragment = renderSelect(sub, ctx.dialect(), scope, depth + 1, false);
        ctx.params().addAll(fragment.params());
        return "(" + fragment.sql() + ")";
    }

    // ------------------------------------------------------------------ selectables

    private static String renderSelectable(Selectable selectable, Scope scope, RenderContext ctx,
                                           int depth, boolean inSelectList) {
        return switch (selectable) {
            case ColumnRef ref -> {
                String qualified = qualifiedColumn(ref, scope, ctx);
                yield inSelectList && ref.outputLabel() != null
                        ? qualified + " AS " + ctx.dialect().quote(ref.outputLabel())
                        : qualified;
            }
            case Expr(var function, var argument, var distinct, var alias) -> {
                String renderedArgument = argument == null ? "*" : (distinct ? "DISTINCT " : "")
                        + qualifiedColumn(argument, scope, ctx);
                String inner = function + "(" + renderedArgument + ")";
                yield inSelectList && alias != null ? inner + " AS " + ctx.dialect().quote(alias) : inner;
            }
        };
    }

    private static String qualifiedColumn(ColumnRef ref, Scope scope, RenderContext ctx) {
        TableRef table = ref.tableAlias() != null
                ? scope.findTableByAlias(ref.tableAlias())
                : scope.findTable(ref.entity());
        if (table == null) {
            String owner = ref.tableAlias() != null
                    ? "alias '" + ref.tableAlias() + "'"
                    : ref.entity().getName();
            throw new SqlBuildException("Column '" + ref.column() + "' belongs to " + owner
                    + " which is not in scope. Available: "
                    + scope.availableTables() + ". Did you forget to join?");
        }
        String alias = scope.aliasOf(table);
        return alias == null ? ctx.dialect().quote(ref.column())
                : ctx.dialect().quote(alias) + "." + ctx.dialect().quote(ref.column());
    }

    // ------------------------------------------------------------------ helpers

    private static String bindOne(RenderContext ctx, Object value) {
        ctx.params().add(value);
        return "?";
    }

    private static String bindBoth(RenderContext ctx, List<Object> values) {
        if (values.size() != 2) {
            throw new SqlBuildException("BETWEEN needs exactly two values, got " + values.size());
        }
        ctx.params().addAll(values);
        return "? AND ?";
    }

    private static <T> void appendJoined(StringBuilder sql, List<T> items, String separator,
                                         java.util.function.Function<T, CharSequence> renderer) {
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sql.append(separator);
            }
            sql.append(renderer.apply(items.get(i)));
        }
    }

    /**
     * One query level during rendering: the model plus its resolved aliases.
     * Sub-queries nest by chaining {@code parent}, so correlated references
     * resolve from the innermost level outwards.
     */
    private static final class Scope {

        private final QueryModel model;
        private final Map<TableRef, String> aliases = new LinkedHashMap<>();
        private final Scope parent;
        private final boolean aliasMode;

        static Scope root(QueryModel model, int depth) {
            return root(model, depth, null);
        }

        static Scope root(QueryModel model, int depth, Scope parent) {
            return new Scope(model, depth, parent);
        }

        private Scope(QueryModel model, int depth, Scope parent) {
            this.model = model;
            this.parent = parent;
            // single-table queries without joins/sub-queries stay alias-free;
            // sub-queries (depth > 0) always use aliases so correlated
            // references stay unambiguous
            this.aliasMode = depth > 0 || model.needsAliases();
            String prefix = depth == 0 ? "t" : "s" + depth + "_";
            List<TableRef> tables = model.getTables();
            for (int i = 0; i < tables.size(); i++) {
                aliases.put(tables.get(i), aliasMode ? prefix + i : null);
            }
        }

    TableRef findTable(Class<?> entity) {
        TableRef own = model.findTable(entity);
        if (own != null) {
            return own;
        }
        return parent == null ? null : parent.findTable(entity);
    }

    TableRef findTableByAlias(String alias) {
        TableRef own = model.findTableByAlias(alias);
        if (own != null) {
            return own;
        }
        return parent == null ? null : parent.findTableByAlias(alias);
    }

    boolean aliasMode() {
        return aliasMode;
    }

    String aliasOf(TableRef ref) {
        if (ref.getExplicitAlias() != null) {
            return ref.getExplicitAlias();
        }
        if (!aliasMode) {
            return null;
        }
        if (aliases.containsKey(ref)) {
            return aliases.get(ref);
        }
        // tables of outer queries: resolve through the scope chain
        return parent == null ? null : parent.aliasOf(ref);
    }

        String availableTables() {
            String own = model.getTables().stream()
                    .map(t -> t.getMeta().getEntityClass().getSimpleName())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            return "[" + own + "]" + (parent == null ? "" : " (and outer tables)");
        }
    }

    /** Accumulates bind parameters while rendering, then freezes the fragment. */
    private static final class RenderContext {
        private final Dialect dialect;
        private final List<Object> params = new ArrayList<>();

        RenderContext(Dialect dialect) {
            this.dialect = dialect;
        }

        Dialect dialect() {
            return dialect;
        }

        List<Object> params() {
            return params;
        }

        SqlFragment toFragment(String sql) {
            return new SqlFragment(sql, params);
        }
    }
}
