// src/main/java/com/example/admission/controller/StatusController.java
package com.example.admission.controller;

import com.example.admission.service.AdmissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/status")
public class StatusController {

    private final AdmissionService admissionService;

    public StatusController(AdmissionService admissionService) {
        this.admissionService = admissionService;
    }

    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkUserStatus(
            @RequestParam String requestId,
            @RequestParam String movieId) {
        
        // isUserInActiveSession: requestId만으로 조회 (sessionId 제거)
        if (admissionService.isUserInActiveSession("movie", movieId, requestId)) {
            return ResponseEntity.ok(Map.of("status", "ACTIVE", "action", "REDIRECT_TO_SEATS"));
        }

        // getUserRank: requestId만으로 조회 (sessionId 제거)
        Long rank = admissionService.getUserRank("movie", movieId, requestId);
        
        if (rank != null) {
            long totalWaiting = admissionService.getTotalWaitingCount("movie", movieId);
            return ResponseEntity.ok(Map.of("status", "WAITING", "rank", rank, "totalWaiting", totalWaiting));
        }
        
        return ResponseEntity.ok(Map.of("status", "NOT_FOUND", "action", "REDIRECT_TO_MOVIES"));
    }
}