package com.example.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * MdcFilter - 요청별 traceId/movieId를 MDC에 주입
 *
 * 모든 HTTP 요청에 traceId(8자)를 부여하고,
 * movieId 파라미터가 있으면 함께 MDC에 넣는다.
 *
 * logback-spring.xml에서 %X{traceId}, %X{movieId}로 참조 가능하다.
 * Loki/Grafana에서 traceId로 분산 로그 추적에 활용된다.
 */
@Component
public class MdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            // 8자 UUID prefix - 로그 가독성과 Loki 검색 편의성 확보
            MDC.put("traceId", UUID.randomUUID().toString().substring(0, 8));

            // movieId가 쿼리 파라미터 또는 경로에 있으면 MDC에 추가
            String movieId = request.getParameter("movieId");
            if (movieId != null && !movieId.isBlank()) {
                MDC.put("movieId", movieId);
            }

            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
