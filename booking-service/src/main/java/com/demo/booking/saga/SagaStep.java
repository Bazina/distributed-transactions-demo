package com.demo.booking.saga;

/** One step and the thing that undoes it. A compensation is a new action, not a rollback. */
public record SagaStep(String name, Runnable action, Runnable compensation) {

    public static SagaStep terminal(String name, Runnable action) {
        return new SagaStep(name, action, () -> {
        });
    }
}
