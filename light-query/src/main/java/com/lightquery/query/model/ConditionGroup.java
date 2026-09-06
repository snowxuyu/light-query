package com.lightquery.query.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A group of conditions. Each child carries its own connector (AND/OR) to
 * the previous sibling, which is how the fluent {@code .or()} continuation
 * works. Children are {@link Condition} or nested {@link ConditionGroup}.
 */
public final class ConditionGroup {

    /** One child plus the connector joining it to the previous sibling. */
    public record Node(Object child, boolean or) {
    }

    private final List<Node> children = new ArrayList<>();
    private boolean pendingOr;

    public List<Node> getChildren() {
        return children;
    }

    /** Adds a child; the connector is decided by the last {@link #or()} call. */
    public void add(Object child) {
        children.add(new Node(child, pendingOr));
        pendingOr = false;
    }

    /** Marks the next added condition/group to be joined with OR. */
    public void or() {
        pendingOr = true;
    }

    public boolean isEmpty() {
        return children.isEmpty();
    }
}
