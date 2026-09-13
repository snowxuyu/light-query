package com.lightquery;

/**
 * The entity-level write operation a {@link FillListener#onWrite} callback
 * fired for. Upsert with a null primary key degenerates to a plain insert
 * and reports {@link #INSERT}.
 */
public enum Operation {
    INSERT, UPDATE, UPSERT
}
