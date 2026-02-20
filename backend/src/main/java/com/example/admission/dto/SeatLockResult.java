package com.example.admission.dto;

import java.util.List;

public record SeatLockResult(
        String status,
        List<String> conflictSeats,
        Long lockedUntil
) {

    public static SeatLockResult locked(long ttlEpochMs) {
        return new SeatLockResult("LOCKED", List.of(), ttlEpochMs);
    }

    public static SeatLockResult conflict(List<String> conflicts) {
        return new SeatLockResult("CONFLICT", conflicts, null);
    }
}
