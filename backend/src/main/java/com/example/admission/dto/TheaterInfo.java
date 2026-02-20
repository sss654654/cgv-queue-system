package com.example.admission.dto;

public record TheaterInfo(
        String theaterId,
        String name,
        int totalSeats,
        int availableSeats
) {
}
