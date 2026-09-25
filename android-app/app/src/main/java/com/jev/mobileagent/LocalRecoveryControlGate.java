package com.jev.mobileagent;

import java.io.IOException;

/** Serializes recovery decisions against pause/cancel requests arriving during async callbacks. */
final class LocalRecoveryControlGate {
    private long generation;
    private String command = "";
    private String reason = "";

    synchronized long beginOperation() {
        command = "";
        reason = "";
        return ++generation;
    }

    synchronized void requestControl(String requestedCommand, String requestedReason) {
        command = requestedCommand == null ? "" : requestedCommand;
        reason = requestedReason == null ? "" : requestedReason;
        generation++;
    }

    synchronized String command() {
        return command;
    }

    synchronized String reason() {
        return reason;
    }

    synchronized boolean hasRequest() {
        return !command.isEmpty();
    }

    synchronized boolean isCurrent(long expectedGeneration) {
        return expectedGeneration == generation && command.isEmpty();
    }

    synchronized boolean runIfCurrent(long expectedGeneration, Operation operation) throws IOException {
        return isCurrent(expectedGeneration) && operation.run();
    }

    synchronized boolean runIfUncontrolled(Operation operation) throws IOException {
        return command.isEmpty() && operation.run();
    }

    synchronized void runExclusive(Runnable operation) {
        operation.run();
    }

    interface Operation {
        boolean run() throws IOException;
    }
}
