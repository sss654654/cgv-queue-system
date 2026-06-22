# CGV 대기열 시스템 — Frontend

대기열 시스템의 React SPA. **대기열 진입 → 대기(실시간 순위) → 입장 → 좌석/결제** 흐름.

> **현재 상태 (2026.06):** 대기열 흐름(진입·대기·입장)은 백엔드와 **실연동**. 좌석·결제는 **mock**(백엔드 미연동). 로컬 k3s 재구축에 맞춰 정리 예정.

## 스택

- React 19 + Vite 7, react-router 7
- `@stomp/stompjs` (WebSocket / STOMP)
- 상태관리: Context + useReducer (외부 라이브러리 없음)

## 백엔드 연동

- **REST:** `/api/sessions/issue`, `/api/movies`, `/api/admission/enter|leave|status`, `/api/coupons/request` 등
- **WebSocket(STOMP):**
  - `/topic/stats/{movieId}` — 전체 대기 통계 방송
  - `/topic/admit/{requestId}` — 개인 입장 알림
  - WS 끊기면 `GET /api/admission/status` 3초 폴링으로 자동 fallback(이중화)
- 연동 주소: `VITE_API_BASE`, `VITE_WEBSOCKET_URL` (빌드타임 env)

## 로컬 실행

```bash
npm install

# 로컬 백엔드에 붙이려면 .env.local 에:
#   VITE_API_BASE=http://localhost:8080
#   VITE_WEBSOCKET_URL=ws://localhost:8080/ws    # ← 백엔드 WS 엔드포인트 확인 필요
npm run dev
```

## 주의 (재구축 시 정리 대상)

- ⚠️ **`.env.production`이 AWS 배포 주소**(`dev.api.peacemaker.kr`)로 하드코딩 → 로컬 연동 시 위처럼 override 필요.
- ⚠️ **STOMP 토픽/ WS 경로가 백엔드와 일부 불일치** — 프론트는 `/topic/admit`·`/ws-stomp`를 가정하는데 백엔드는 `/topic/admission`·`/ws`일 수 있음. **연동 전 정합 확인 필수.**
- ⚠️ 좌석·결제 페이지는 현재 mock 데이터 + 시뮬레이션(백엔드 `/api/seats`·`/api/admission/complete` 미호출).
