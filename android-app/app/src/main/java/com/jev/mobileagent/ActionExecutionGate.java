package com.jev.mobileagent;

/**
 * Serializes task-control invalidation with the final device-side action
 * dispatch.  The lock is held only while the Accessibility side effect is
 * entered, so a control request either wins before dispatch or observes an
 * already-started action and lets its callback produce the receipt.
 */
public final class ActionExecutionGate {
    public static final class Token {
        private final ActionExecutionGate owner;
        private final long generation;

        private Token(ActionExecutionGate owner, long generation) {
            this.owner = owner;
            this.generation = generation;
        }

        /** Run the side effect only if this task run is still current. */
        public boolean runIfCurrent(Runnable sideEffect) {
            return owner.runIfCurrent(this, sideEffect);
        }
    }

    private final Object monitor = new Object();
    private long generation;

    /** Starts a new run and invalidates every token from an older run. */
    public Token begin() {
        synchronized (monitor) {
            return new Token(this, ++generation);
        }
    }

    /** Invalidates queued actions before the remote control request is sent. */
    public void invalidate() {
        synchronized (monitor) {
            generation++;
        }
    }

    private boolean runIfCurrent(Token token, Runnable sideEffect) {
        if (sideEffect == null) {
            throw new IllegalArgumentException("sideEffect must not be null");
        }
        synchronized (monitor) {
            if (token == null || token.owner != this || token.generation != generation) {
                return false;
            }
            // Keep invalidation from passing between the validity check and
            // the call into Accessibility's actual side-effect API.
            sideEffect.run();
            return true;
        }
    }
}
