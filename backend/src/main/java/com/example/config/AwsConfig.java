package com.example.config;

import org.springframework.context.annotation.Configuration;

/**
 * AwsConfig - AWS SDK 설정
 *
 * Kinesis 제거 완료 (Round 10): 이벤트 스트리밍은 Redis Pub/Sub로 대체.
 * 현재 AWS SDK 의존성이 불필요하여 빈 설정만 유지한다.
 *
 * 향후 S3 (이미지 업로드 등) 필요 시 여기에 S3Client Bean을 추가한다.
 * IRSA 자격증명은 EKS Pod에 자동 주입되므로 별도 설정 불필요.
 */
@Configuration
public class AwsConfig {
    // Kinesis 제거 후 빈 설정 유지
    // AWS SDK BOM도 pom.xml에서 제거됨
}
