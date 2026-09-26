package com.demo.booking.model;

/** The booking cannot be made. Turns into 409 at the edge. */
public class BookingRejected extends RuntimeException {
    public BookingRejected(String reason) {
        super(reason);
    }
}
