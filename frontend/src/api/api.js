// src/api/api.js - 완전히 수정된 버전
import { v4 as uuidv4 } from 'uuid';

const API_BASE_URL = import.meta.env.VITE_API_BASE;

// ✅ 안전한 JSON 파싱 유틸리티 (새로 추가)
async function safeJsonParse(response) {
  const text = await response.text();
  
  if (!text.trim()) {
    console.warn('⚠️ 서버에서 빈 응답을 받았습니다.');
    return null;
  }
  
  try {
    return JSON.parse(text);
  } catch (e) {
    console.error('❌ JSON 파싱 실패. 서버 응답:', text.substring(0, 200));
    
    // HTML 에러 페이지인지 확인
    if (text.includes('<html>') || text.includes('<!DOCTYPE')) {
      throw new Error(`서버에서 오류 페이지를 반환했습니다 (HTTP ${response.status})`);
    }
    
    throw new Error('서버 응답 형식이 올바르지 않습니다.');
  }
}

// ✅ 수정된 재시도 로직이 있는 HTTP 클라이언트
async function apiRequest(url, options = {}, retryCount = 3) {
  const defaultOptions = {
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json', // Accept 헤더 추가
      ...options.headers,
    },
    ...options,
  };

  for (let attempt = 1; attempt <= retryCount; attempt++) {
    try {
      console.log(`🌐 API 요청 (시도 ${attempt}/${retryCount}): ${url}`);
      
      const response = await fetch(url, defaultOptions);
      
      if (response.status === 204) {
        console.log(`✅ API 응답 성공 (${attempt}/${retryCount}): No Content`);
        return { response, data: null };
      }
      
      if (response.ok) {
        const data = await safeJsonParse(response);
        console.log(`✅ API 응답 성공 (${attempt}/${retryCount}):`, data);
        return { response, data };
      }
      
      if (response.status >= 400 && response.status < 500) {
        const errorData = await safeJsonParse(response);
        console.error(`❌ 클라이언트 오류 (${response.status}):`, errorData);
        throw new Error(errorData?.message || `HTTP ${response.status} 오류`);
      }
      
      if (response.status >= 500) {
        console.warn(`⚠️ 서버 오류 (시도 ${attempt}/${retryCount}): ${response.status}`);
        if (attempt === retryCount) {
          const errorData = await safeJsonParse(response);
          throw new Error(errorData?.message || `서버 오류: HTTP ${response.status}`);
        }
        await new Promise(resolve => setTimeout(resolve, Math.pow(2, attempt) * 1000));
        continue;
      }
      
    } catch (error) {
      console.error(`❌ API 요청 실패 (시도 ${attempt}/${retryCount}):`, error);
      
      if (error.name === 'TypeError' || error.message.includes('fetch')) {
        if (attempt === retryCount) {
          throw new Error('네트워크 연결 오류. 인터넷 연결을 확인하고 다시 시도해주세요.');
        }
        await new Promise(resolve => setTimeout(resolve, Math.pow(2, attempt) * 1000));
        continue;
      }
      
      throw error;
    }
  }
}

function getOrCreateSessionId() {
  let sessionId = sessionStorage.getItem('sessionId');
  if (!sessionId) {
    sessionId = uuidv4();
    sessionStorage.setItem('sessionId', sessionId);
  }
  return sessionId;
}

function generateUUID() {
  return uuidv4();
}

export async function issueSession() {
  try {
    console.log('🍪 백엔드 세션 쿠키 발급 요청...');
    const { response } = await apiRequest(`${API_BASE_URL}/api/sessions/issue`, {
      method: 'POST',
    });
    if (response.status === 204) {
      console.log('✅ 백엔드 세션 쿠키 발급 완료');
    }
  } catch (error) {
    console.error('❌ 백엔드 세션 쿠키 발급 실패:', error);
  }
}

export async function fetchMovies() {
  try {
    console.log(`🎬 영화 목록 조회 중... (from: ${API_BASE_URL})`);
    const { data } = await apiRequest(`${API_BASE_URL}/api/movies`);
    return data;
  } catch (error) {
    console.error('❌ 영화 목록 조회 실패:', error);
    throw new Error(`영화 목록을 불러올 수 없습니다: ${error.message}`);
  }
}

export async function enterMovieQueue(movieId) {
  const sessionId = getOrCreateSessionId();
  const requestId = generateUUID();
  sessionStorage.setItem('lastRequestId', requestId);
  
  const endpoint = `${API_BASE_URL}/api/admission/enter`;
  const requestPayload = { movieId, sessionId, requestId };

  console.log(`🎯 대기열 진입 요청 전송`, requestPayload);
  
  try {
    const { response, data } = await apiRequest(endpoint, {
      method: 'POST',
      body: JSON.stringify(requestPayload)
    }, 5);

    console.log('📥 대기열 진입 응답:', { status: response.status, data });

    let systemConfig = null;
    try {
      systemConfig = await fetchSystemConfig();
    } catch (configError) {
      console.warn('⚠️ 시스템 설정 조회 실패, 기본값 사용:', configError);
    }

    if (response.status === 200) { // 즉시 입장
      return { status: 'SUCCESS', sessionId, systemConfig, ...data };
    } else if (response.status === 202) { // 대기열 등록
      return { status: 'QUEUED', sessionId, systemConfig, ...data };
    } else {
      throw new Error(`예상치 못한 응답 코드: ${response.status}`);
    }
    
  } catch (error) {
    console.error(`❌ 대기열 진입 실패:`, error);
    if (error.message.includes('Redis') || error.message.includes('CROSSSLOT')) {
      throw new Error('서버에서 일시적인 데이터 처리 오류가 발생했습니다. 잠시 후 다시 시도해주세요.');
    }
    if (error.message.includes('서버에서 오류 페이지를 반환')) {
      throw new Error('서버에서 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.');
    }
    if (error.name === 'TypeError' || error.message.includes('네트워크')) {
      throw new Error('네트워크 오류가 발생했습니다. 연결을 확인하고 다시 시도해주세요.');
    }
    throw error;
  }
}

export async function leaveMovieQueue(movieId, sessionId, requestId) {
  try {
    console.log('🚪 대기열 퇴장 요청:', { movieId, sessionId, requestId: requestId?.substring(0, 8) });
    await apiRequest(`${API_BASE_URL}/api/admission/leave`, {
      method: 'POST',
      body: JSON.stringify({ movieId, sessionId, requestId })
    });
    console.log('✅ 대기열 퇴장 완료');
  } catch (error) {
    console.warn('⚠️ 대기열 퇴장 실패 (무시하고 계속):', error.message);
  }
}

// ✅ 개선: 사용자 상태 확인 (폴링 및 페이지 진입 시 사용)
export async function checkUserStatus(movieId, sessionId, requestId) {
  try {
    const { data } = await apiRequest(
      `${API_BASE_URL}/api/admission/status?movieId=${movieId}&sessionId=${sessionId}&requestId=${requestId}`
    );
    return data;
  } catch (error) {
    console.error('❌ 사용자 상태 확인 실패:', error);
    return { status: 'UNKNOWN', message: '상태 확인 실패' };
  }
}

// ✅ 개선: 시스템 설정 조회
export async function fetchSystemConfig() {
  try {
    // 벡엔드 컨트롤러 경로에 맞게 수정
    const { data } = await apiRequest(`${API_BASE_URL}/api/admission/system/config`);
    return data;
  } catch (error) {
    console.warn('⚠️ 시스템 설정 조회 실패:', error);
    return {
      sessionTimeoutSeconds: 30, // 실패 시 기본값
      baseSessionsPerPod: 200,
      calculatedMaxSessions: 400,
      currentPodCount: 2,
      waitTimePerPodSeconds: 10
    };
  }
}

// ✅ 추가: 연결 상태 확인 유틸리티
export async function checkBackendHealth() {
  try {
    const { data } = await apiRequest(`${API_BASE_URL}/health`, {}, 1); // 재시도 1회만
    return { healthy: true, ...data };
  } catch (error) {
    console.error('❌ 백엔드 헬스체크 실패:', error);
    return { healthy: false, error: error.message };
  }
}