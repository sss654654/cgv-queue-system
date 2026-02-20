// src/main/java/com/example/admission/dto/EnterResponse.java
package com.example.admission.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnterResponse {

    public enum Status {
        ADMITTED,   // 즉시 입장 허가 (Active 세션에 추가됨)
        WAITING,    // 대기열에 등록됨 (rank + totalWaiting 포함)
        ERROR       // 입장 처리 실패
    }

    private final Status status;
    private final String message;
    private final String requestId;
    private final Long myRank;
    private final Long totalWaiting;

    public EnterResponse(Status status, String message, String requestId,
                         Long myRank, Long totalWaiting) {
        this.status = status;
        this.message = message;
        this.requestId = requestId;
        this.myRank = myRank;
        this.totalWaiting = totalWaiting;
    }

    // Getters
    public Status getStatus() { return status; }
    public String getMessage() { return message; }
    public String getRequestId() { return requestId; }
    public Long getMyRank() { return myRank; }
    public Long getTotalWaiting() { return totalWaiting; }
}
