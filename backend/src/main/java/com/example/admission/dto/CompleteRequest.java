package com.example.admission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CompleteRequest(
        @NotBlank String movieId,
        @NotBlank String requestId,
        @NotBlank String theaterId,
        @NotEmpty List<String> seatIds
) {
}
