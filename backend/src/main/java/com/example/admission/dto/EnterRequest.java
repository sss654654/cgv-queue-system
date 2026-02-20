package com.example.admission.dto;

import jakarta.validation.constraints.NotBlank;

public class EnterRequest {

    @NotBlank(message = "movieId is required")
    private String movieId;

    @NotBlank(message = "requestId is required")
    private String requestId;

    // 기본 생성자
    public EnterRequest() {}

    // 생성자
    public EnterRequest(String movieId, String requestId) {
        this.movieId = movieId;
        this.requestId = requestId;
    }

    // Record 스타일 메서드들 (기존 코드 호환)
    public String movieId() {
        return movieId;
    }

    public String requestId() {
        return requestId;
    }

    // Getter / Setter
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
