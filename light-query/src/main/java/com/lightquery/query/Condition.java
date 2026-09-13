package com.lightquery.query;

import com.lightquery.query.model.ConditionGroup;

/**
 * An immutable, reusable condition tree built offline through
 * {@link Conditions} — the composition API. Combine trees with
 * {@link #and(Condition)} / {@link #or(Condition)} / {@link #not()}, attach
 * the result to any query with {@code where(spec)}, and reuse the same
 * instance across queries: the tree is never mutated after it is built.
 *
 * <pre>{@code
 * Condition active = Conditions.col(User::getStatus).eq(ACTIVE);
 * Condition spec = active.and(Conditions.col(User::getAge).ge(18)).not();
 * db.queryable(User.class).where(spec).toList();
 * db.updatable(User.class).where(spec).execute();
 * }</pre>
 *
 * <p>Column references resolve against the host query's scope at render time
 * (joined tables work); an entity outside the scope fails with the usual
 * "did you forget to join" error. Attaching a spec composes it as one subtree
 * ANDed into the query's WHERE tree — logic-delete filters and other user
 * conditions are unaffected.</p>
 */
public final class Condition {

    /** Renders to nothing — the result of a skipped boolean-first condition. */
    static final Condition EMPTY = new Condition(new ConditionGroup(), false);

    private final ConditionGroup group;
    private final boolean usesSubQueries;

    Condition(ConditionGroup group, boolean usesSubQueries) {
        this.group = group;
        this.usesSubQueries = usesSubQueries;
    }

    static Condition leaf(com.lightquery.query.model.Condition condition, boolean usesSubQueries) {
        ConditionGroup group = new ConditionGroup();
        group.add(condition);
        return new Condition(group, usesSubQueries);
    }

    /** Composes both trees with AND. */
    public Condition and(Condition other) {
        return combine(other, false);
    }

    /** Composes both trees with OR. */
    public Condition or(Condition other) {
        return combine(other, true);
    }

    /** Negates this tree: {@code NOT (…)}. */
    public Condition not() {
        ConditionGroup outer = new ConditionGroup();
        outer.negate();
        outer.add(this.group);
        return new Condition(outer, usesSubQueries);
    }

    private Condition combine(Condition other, boolean or) {
        // an empty tree renders to nothing, so AND/OR with it is the other side;
        // this keeps skipped boolean conditions from entering the tree at all
        if (group.getChildren().isEmpty()) {
            return other;
        }
        if (other.group.getChildren().isEmpty()) {
            return this;
        }
        ConditionGroup parent = new ConditionGroup();
        parent.add(this.group);
        if (or) {
            parent.or();
        }
        parent.add(other.group);
        return new Condition(parent, usesSubQueries || other.usesSubQueries);
    }

    ConditionGroup group() {
        return group;
    }

    boolean usesSubQueries() {
        return usesSubQueries;
    }
}
