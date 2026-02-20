package com.example.admission.dto;

import jakarta.validation.constraints.NotBlank;

public class LeaveRequest {

    @NotBlank(message = "movieId is required")
    private String movieId;

    @NotBlank(message = "requestId is required")
    private String requestId;

    // 기본 생성자
    public LeaveRequest() {}

    // 생성자
    public LeaveRequest(String movieId, String requestId) {
        this.movieId = movieId;
        this.requestId = requestId;
    }

    // Getters and Setters
    public String getMovieId() {
        return movieId;
    }

    public void setMovieId(String movieId) {
        this.movieId = movieId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
