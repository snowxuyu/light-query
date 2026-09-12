package com.lightquery.query;

import com.lightquery.TableColumn;
import com.lightquery.lambda.SFunction;
import com.lightquery.query.model.Condition;
import com.lightquery.query.model.ConditionGroup;
import com.lightquery.query.model.Operator;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * ON-condition builder for joins. The primary form compares two columns of
 * the joined entities: {@code on -> on.col(User::getId).eq(Order::getUserId)}.
 * Self-join occurrences are addressed with {@code TableColumn}s:
 * {@code on.eqColumn(staff.col(Employee::getManagerId), manager.col(Employee::getId))}.
 * Conditions against constant values are also supported; {@code .or()}
 * switches the connector of the next condition.
 *
 * @param <A> the entity that is already in the query
 * @param <B> the entity being joined
 */
public final class JoinOn<A, B> {

    private final ConditionGroup group;
    private final ColumnResolver resolver;
    private final Runnable onSubQuery;

    JoinOn(ConditionGroup group, ColumnResolver resolver, Runnable onSubQuery) {
        this.group = group;
        this.resolver = resolver;
        this.onSubQuery = onSubQuery;
    }

    // --------------------------------------------- column-to-column via typed handles
    // Use col(...).eqColumn(...) so both sides share the value type.
    // (The old eq(colA, colB) wildcard overloads were removed: they allowed
    // comparing columns of different types.)

    // --------------------------------------------- TableColumn (self-join occurrences)

    public <V> JoinOn<A, B> eqColumn(TableColumn<?, V> colA, TableColumn<?, V> colB) {
        return addColumns(colA, Operator.EQ, colB);
    }

    public <V> JoinOn<A, B> neColumn(TableColumn<?, V> colA, TableColumn<?, V> colB) {
        return addColumns(colA, Operator.NE, colB);
    }

    public <V> JoinOn<A, B> gtColumn(TableColumn<?, V> colA, TableColumn<?, V> colB) {
        return addColumns(colA, Operator.GT, colB);
    }

    public <V> JoinOn<A, B> geColumn(TableColumn<?, V> colA, TableColumn<?, V> colB) {
        return addColumns(colA, Operator.GE, colB);
    }

    public <V> JoinOn<A, B> ltColumn(TableColumn<?, V> colA, TableColumn<?, V> colB) {
        return addColumns(colA, Operator.LT, colB);
    }

    public <V> JoinOn<A, B> leColumn(TableColumn<?, V> colA, TableColumn<?, V> colB) {
        return addColumns(colA, Operator.LE, colB);
    }

    /**
     * Starts a strongly-typed condition on a property — both the constant
     * family ({@code on.col(User::getId).eq(5L)}) and the column-to-column
     * family ({@code on.col(User::getId).eqColumn(Order::getUserId)}) are
     * checked against the property type at compile time.
     */
    public <C, V> TypedColumn<JoinOn<A, B>, V> col(SFunction<C, V> col) {
        ColumnResolver.Resolved resolved = resolver.resolve(col);
        return new TypedColumn<>(this, group, resolved.ref(), resolved.meta(), resolver, onSubQuery);
    }


    /** Applies {@code block} only when {@code condition} is true. */
    public JoinOn<A, B> when(boolean condition, Consumer<JoinOn<A, B>> block) {
        if (condition) {
            block.accept(this);
        }
        return this;
    }

    /**
     * Appends a raw SQL fragment to the ON clause (escape hatch for dialect
     * functions the typed API does not cover). The fragment is emitted
     * verbatim inside parentheses; every {@code ?} binds the matching
     * param — never concatenate values into the fragment. A
     * placeholder/param count mismatch fails when the SQL is rendered.
     */
    public JoinOn<A, B> whereRaw(String fragment, Object... params) {
        group.add(Condition.raw(fragment, params == null ? List.of() : Arrays.asList(params)));
        return this;
    }

    /** Switches the connector of the next added condition to OR. */
    public JoinOn<A, B> or() {
        group.or();
        return this;
    }

    private JoinOn<A, B> addColumns(TableColumn<?, ? > colA, Operator operator, TableColumn<?, ? > colB) {
        group.add(Condition.ofColumns(resolver.resolve(colA).ref(), operator, resolver.resolve(colB).ref()));
        return this;
    }

    ConditionGroup group() {
        return group;
    }
}
