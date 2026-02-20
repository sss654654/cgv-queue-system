package com.example.admission.controller;

import com.example.admission.service.AdmissionMetricsService;
import com.example.admission.service.AdmissionService;
import com.example.admission.ws.WebSocketUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/debug")
@Tag(name = "Debug", description = "시스템 내부 상태 확인용 디버그 API")
public class DebugController {

    private final AdmissionService admissionService;
    private final AdmissionMetricsService metricsService;
    private final WebSocketUpdateService webSocketUpdateService;

    public DebugController(AdmissionService admissionService,
                           AdmissionMetricsService metricsService,
                           WebSocketUpdateService webSocketUpdateService) {
        this.admissionService = admissionService;
        this.metricsService = metricsService;
        this.webSocketUpdateService = webSocketUpdateService;
    }

    @Operation(summary = "전체 시스템 상태 종합 조회", description = "주요 컴포넌트들의 상태와 통계를 한 번에 확인합니다.")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getFullSystemStatus() {
        Map<String, Object> status = new HashMap<>();

        try {
            status.put("systemSummary", metricsService.getSystemSummary());
            status.put("webSocketStats", webSocketUpdateService.getWebSocketStats());
        } catch (Exception e) {
            status.put("error", "상태 조회 중 오류 발생: " + e.getMessage());
        }

        return ResponseEntity.ok(status);
    }
}
