package com.example.admission.dto;

public class EnterRequest {
    private String movieId;
    private String sessionId;
    private String requestId;

    // 기본 생성자
    public EnterRequest() {}

    // 생성자
    public EnterRequest(String movieId, String sessionId, String requestId) {
        this.movieId = movieId;
        this.sessionId = sessionId;
        this.requestId = requestId;
    }

    // Record 스타일 메서드들 (컴파일 에러 해결)
    public String movieId() {
        return movieId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String requestId() {
        return requestId;
    }

    // 일반적인 getter 메서드들
    public String getMovieId() {
        return movieId;
    }

    public void setMovieId(String movieId) {
        this.movieId = movieId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}