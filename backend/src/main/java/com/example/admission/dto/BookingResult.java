package com.example.admission.dto;

public record BookingResult(
        String status,
        long completedCount,
        long remainingSeats,
        boolean soldOut
) {
}
