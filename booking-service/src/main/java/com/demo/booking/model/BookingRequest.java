package com.demo.booking.model;

/**
 * One booking. Times are plain strings in 'yyyy-MM-dd HH:mm:ss' so the demo
 * scripts can pass them through untouched and the students can read them in the
 * log and in the table without a conversion step in between.
 */
public record BookingRequest(int roomId, int userId, String startsAt, String endsAt) {
}
