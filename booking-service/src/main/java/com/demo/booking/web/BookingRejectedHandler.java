package com.demo.booking.web;

import com.demo.booking.model.BookingRejected;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Turns a rejected booking into 409 once, instead of in every controller. */
@RestControllerAdvice
public class BookingRejectedHandler {

    @ExceptionHandler(BookingRejected.class)
    public ResponseEntity<Map<String, Object>> onRejected(BookingRejected e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("status", "REJECTED", "reason", String.valueOf(e.getMessage())));
    }
}
