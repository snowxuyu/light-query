package com.lightquery.exec;

import com.lightquery.FillListener;

/**
 * Process-wide holder of the registered {@link FillListener}. Internal wiring
 * between the static facade ({@code LightQuery.setFillListener}) and the
 * entity operations; application code should use the facade methods.
 */
public final class FillListeners {

    private static volatile FillListener listener;

    private FillListeners() {
    }

    /** The registered listener, or null when none is registered. */
    public static FillListener current() {
        return listener;
    }

    /** Registers (or replaces) the listener; null clears it. */
    public static void set(FillListener registered) {
        listener = registered;
    }

    /** Clears the registered listener. */
    public static void clear() {
        listener = null;
    }
}
