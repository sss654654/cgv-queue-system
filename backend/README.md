# CGV 대기열 시스템 — Backend

영화 티켓팅 오픈 시 폭증하는 트래픽을 정원제로 흘려보내는 **실시간 대기열 시스템**.

> **⚠️ 현재 상태 (2026.06 — 재구축 중).**
> 원래 팀 프로젝트(AWS/EKS)를 **개인이 로컬 온프레미스 k3s 기준으로 처음부터 재구축**하는 중입니다.
> 이 README는 **현재 코드의 실제 상태**를 반영합니다(과거 AWS 마케팅 문구 아님).
> - **`infra/`(Terraform)·`platform/`(Helm) 삭제됨** → 로컬 k3s 기준 재작성 예정. (옛 버전은 git history에 있음.)
> - **Kinesis 제거됨** → 이벤트 전파는 Redis Pub/Sub로 대체. **코드에 AWS SDK 없음**(`AwsConfig`는 빈 껍데기).
> - **언어:** 현재 Java/Spring Boot. **Go 재작성 검토 중**(큐 Lua는 그대로 이식, 손계측 4축 옵저버빌리티 추가 목표).

---

## 무엇을 하는가

- 활성 세션 정원이 차면 들어온 사용자를 **Redis Sorted Set 대기열**에 줄 세우고, 자리가 나면 순서대로 입장시킨다.
- 순위는 **broadcast-only**: 개별 `ZRANK`를 매번 돌리지 않고 전체 대기/처리 수만 주기적으로 방송, 클라이언트가 `초기순번 − 처리수`로 자기 순위를 계산(서버 O(N) 회피).
- **WebSocket(STOMP)**으로 입장·통계 실시간 알림. 멀티 Pod는 **Redis Pub/Sub 단일 채널**로 동기화(어느 Pod 이벤트든 모든 Pod 클라이언트가 수신).

## 실제 구현 (코드 기준)

| 컴포넌트 | 역할 |
|---|---|
| `AdmissionService` | 입장/대기/승격/퇴장. **인라인 Lua**로 원자 처리(중복체크 + 입장/대기 분기, 배치 승격). 키에 Hash Tag `{movieId}`. |
| `QueueProcessor` (`@Scheduled` 2s) | 빈 슬롯만큼 대기열 승격. `LoadBalancingOptimizer`로 Pod 간 처리 분배. |
| `SessionTimeoutProcessor` (10s) | 만료 활성 세션 정리. |
| `RealtimeStatsBroadcaster` (1s) | 영화별 통계 Pub/Sub 방송(broadcast-only). |
| `SeatService` | `seat_lock.lua` all-or-nothing 좌석 선점(최대 4석, TTL 300s). |
| `DynamicSessionCalculator` | K8s API(`client-java`)로 Pod 수 조회 → 동적 최대 세션. |
| `QueueMetrics` | Prometheus 메트릭(`/actuator/prometheus`, KEDA trigger용). |
| `RoutingDataSource` | MySQL read/write 분리(트랜잭션 `readOnly` 기반). |

## 기술 스택

- Java 17, Spring Boot 3.3, Spring Data JPA, Spring WebSocket (STOMP)
- Redis (Sorted Set + Pub/Sub + Lua), MySQL
- 관측: Micrometer/Prometheus(메트릭) + Logstash JSON 로그(Loki용). **trace(OTel)·profile(Pyroscope)은 미구현 — 재구축에서 직접 추가 예정.**

## 로컬 실행

```bash
# 1) 빌드 (compose가 ./target/classes 를 마운트하므로 먼저 빌드)
./mvnw clean package -DskipTests

# 2) 인프라 + 앱 (mysql + redis + api)
docker compose up

# 3) 확인
curl localhost:8080/health
docker stats --no-stream      # 풋프린트 측정
```

- `docker-compose.yml` = **mysql + redis + api** 만 띄움(LocalStack/Kinesis 제거됨). `env_file: ./.env.local`.
- redis 호스트는 `local` 프로필 설정을 따름 — 연결 실패 시(`localhost:6379`) 컨테이너명 `redis`로 잡혀야 함.
- 프론트엔드는 별도(`../frontend`) — 로컬 연동 시 `VITE_API_BASE`를 `http://localhost:8080`으로 override.

## 알려진 현재 상태 / 재구축 TODO

- **예매 정석 경로(`BookingService` + `booking_complete.lua`) 미사용(데드)** — 컨트롤러 인라인 로직이 대체.
- **`theaters` 테이블 시드 없음** → 상영관 조회 빈 결과. (Flyway 시드 추가 필요.)
- **trace/profile 미구현** → 4축 옵저버빌리티(metric/log/trace/profile) 직접 추가 예정.
- AWS 결합 잔재 정리 예정(CORS 하드코딩 도메인, IRSA 전제 등).
- 일부 메트릭은 정의만 있고 미호출(항상 0).

## 원본 프로젝트

2025.08 팀 프로젝트(5명 + 멘토, CJ 올리브네트웍스 cloudwave)에서 시작. 본인 담당: Spring Boot 백엔드 + Helm 환경변수.
당시 AWS/EKS 기반 상세 문서(부하 테스트·AWS 트러블슈팅 등)는 **git history의 이전 커밋**에 보존되어 있음.
