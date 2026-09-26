package com.demo.booking.model;

/** Times are strings in 'yyyy-MM-dd HH:mm:ss' so they read the same everywhere. */
public record BookingRequest(int roomId, int userId, String startsAt, String endsAt) {
}
