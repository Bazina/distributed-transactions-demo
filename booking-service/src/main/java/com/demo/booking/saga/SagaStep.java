package com.demo.booking.saga;

/**
 * One step and the thing that undoes it. A compensation is a new action, not a rollback.
 *
 * The step knows its own number, so nothing has to count positions in a list.
 */
public record SagaStep(int number, String name, Runnable action, Runnable compensation) {

    /** Reserve runs before the step list exists, because it creates the booking id. */
    public static final int RESERVE = 1;
    public static final int FIRST_AFTER_RESERVE = RESERVE + 1;

    public static SagaStep terminal(int number, String name, Runnable action) {
        return new SagaStep(number, name, action, () -> {
        });
    }
}
