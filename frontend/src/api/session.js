// src/api/session.js

const API_BASE = import.meta.env.VITE_API_BASE;

/** 세션 쿠키 발급(또는 갱신). 서버는 Set-Cookie로 SID 내려줌(204 예상). */
export async function ensureSession() {
  const res = await fetch(`${API_BASE}/api/sessions/issue`, { // 경로 수정: session -> sessions
    method: 'POST', // GET -> POST로 변경하는 것이 RESTful API 원칙에 더 맞음
    credentials: 'include', // ★ 쿠키 주고받기 필수
  });
  if (!res.ok) throw new Error(`session issue failed: ${res.status}`);
}

/**
 * 세션 쿠키 포함해서 fetch.
 * 401/419(세션 없음/만료)면 세션 발급 후 1회 자동 재시도.
 */
export async function fetchWithSession(input, init = {}) {
  const first = await fetch(input, { ...init, credentials: 'include' });
  if (first.status === 401 || first.status === 419) {
    await ensureSession();
    return fetch(input, { ...init, credentials: 'include' });
  }
  return first;
}