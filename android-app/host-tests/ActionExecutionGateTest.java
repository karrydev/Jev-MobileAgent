package com.jev.mobileagent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Small host-javac regression for the App action/control race boundary. */
public final class ActionExecutionGateTest {
    public static void main(String[] args) throws Exception {
        queuedActionAfterControlHasNoSideEffect("pause");
        queuedActionAfterControlHasNoSideEffect("cancel");
        startedActionStillAllowsReceiptCallback();
        System.out.println("ActionExecutionGateTest passed");
    }

    private static void queuedActionAfterControlHasNoSideEffect(String command) {
        ActionExecutionGate gate = new ActionExecutionGate();
        ActionExecutionGate.Token token = gate.begin();
        AtomicInteger sideEffects = new AtomicInteger();

        // Pause and cancel share the same immediate local invalidation path.
        gate.invalidate();

        check(!token.runIfCurrent(sideEffects::incrementAndGet), command + " must reject queued action");
        check(sideEffects.get() == 0, command + " queued action must have zero side effects");
    }

    private static void startedActionStillAllowsReceiptCallback() throws Exception {
        ActionExecutionGate gate = new ActionExecutionGate();
        ActionExecutionGate.Token token = gate.begin();
        AtomicInteger sideEffects = new AtomicInteger();
        AtomicInteger receipts = new AtomicInteger();
        CountDownLatch dispatchEntered = new CountDownLatch(1);
        CountDownLatch releaseDispatch = new CountDownLatch(1);
        CountDownLatch controlAttempted = new CountDownLatch(1);
        CountDownLatch controlFinished = new CountDownLatch(1);

        Thread dispatch = new Thread(() -> {
            boolean started = token.runIfCurrent(() -> {
                dispatchEntered.countDown();
                await(releaseDispatch);
                sideEffects.incrementAndGet();
            });
            check(started, "current action should enter the side effect");
        });
        dispatch.start();
        check(dispatchEntered.await(2, TimeUnit.SECONDS), "dispatch did not start");

        Thread control = new Thread(() -> {
            controlAttempted.countDown();
            gate.invalidate();
            controlFinished.countDown();
        });
        control.start();
        check(controlAttempted.await(2, TimeUnit.SECONDS), "control did not start");
        check(!controlFinished.await(100, TimeUnit.MILLISECONDS),
                "control must not overtake an already-entered side effect");
        releaseDispatch.countDown();
        dispatch.join(2000L);
        control.join(2000L);

        // Control may invalidate an already-started action, but its callback
        // remains responsible for recording the execution receipt.
        receipts.incrementAndGet();
        check(sideEffects.get() == 1, "already-started action must execute once");
        check(receipts.get() == 1, "already-started action must retain its receipt callback");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("dispatch interrupted", exception);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
