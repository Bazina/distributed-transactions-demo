package com.demo.booking.saga;

/**
 * Where the demo breaks the saga on purpose. Teaching scaffolding, not business logic.
 *
 * failAt stops a step before it runs and lets the saga compensate.
 * killAt stops the orchestrator after a step, compensating nothing, which is
 * what a real crash looks like.
 */
public record FailureInjection(int failAt, int killAt) {

    public static FailureInjection none() {
        return new FailureInjection(0, 0);
    }

    public boolean failsBefore(SagaStep step) {
        return failAt == step.number();
    }

    public boolean killsAfter(SagaStep step) {
        return killAt == step.number();
    }

    public boolean killsAfterReserve() {
        return killAt == SagaStep.RESERVE;
    }
}
