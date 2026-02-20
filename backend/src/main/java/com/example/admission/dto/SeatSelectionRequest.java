package com.example.admission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SeatSelectionRequest(
        @NotBlank String movieId,
        @NotBlank String theaterId,
        @NotEmpty @Size(max = 4) List<String> seatIds,
        @NotBlank String requestId
) {
}
