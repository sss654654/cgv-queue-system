// src/pages/WaitPage.jsx - 부하 상황 대응 개선 버전
import React, { useEffect, useState, useRef, useContext, useCallback } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Client } from '@stomp/stompjs';
import { AppContext } from '../context/AppContext';
import styles from './WaitPage.module.css';
import { leaveMovieQueue, fetchSystemConfig, checkUserStatus } from '../api/api';

export default function WaitPage() {
    const navigate = useNavigate();
    const location = useLocation();
    const clientRef = useRef(null);
    const statusCheckIntervalRef = useRef(null);
    const reconnectTimeoutRef = useRef(null);
    const heartbeatCheckIntervalRef = useRef(null);
    const { state } = useContext(AppContext);

    // URL 파라미터 추출
    const queryParams = new URLSearchParams(location.search);
    const movieId = queryParams.get('movieId');
    const requestId = queryParams.get('requestId');
    const sessionId = queryParams.get('sessionId');
    const movieTitle = state.booking?.selectedMovie?.title || '영화';

    // 상태 관리
    const [myRank, setMyRank] = useState(location.state?.initialRank || null);
    const [totalWaiting, setTotalWaiting] = useState(location.state?.initialTotal || null);
    const [connectionStatus, setConnectionStatus] = useState('connecting');
    const [statusMessage, setStatusMessage] = useState('대기열에 연결하고 있습니다...');
    const [isAdmitted, setIsAdmitted] = useState(false);
    const [estimatedWaitTime, setEstimatedWaitTime] = useState('');
    const [systemConfig, setSystemConfig] = useState(null);
    const [lastUpdateTime, setLastUpdateTime] = useState(Date.now());
    const [isConnected, setIsConnected] = useState(false);
    const [reconnectAttempts, setReconnectAttempts] = useState(0);
    const [lastHeartbeat, setLastHeartbeat] = useState(Date.now());
    const [usingApiPolling, setUsingApiPolling] = useState(false);

    // 좌석 페이지로 이동
    const redirectToSeats = useCallback(() => {
        if (isAdmitted) return;
        
        console.log('🎬 좌석 페이지로 이동 시작');
        
        if (!requestId || !sessionId || !movieId) {
            console.error('❌ 필수 파라미터 누락:', { requestId, sessionId, movieId });
            alert('필수 정보가 누락되었습니다. 영화 선택 페이지로 돌아갑니다.');
            navigate('/movies');
            return;
        }
        
        setIsAdmitted(true);
        setStatusMessage('🎉 입장 확인! 좌석 선택 페이지로 이동합니다...');
        
        // 모든 인터벌 정리
        [statusCheckIntervalRef, heartbeatCheckIntervalRef, reconnectTimeoutRef].forEach(ref => {
            if (ref.current) {
                clearInterval(ref.current);
                clearTimeout(ref.current);
                ref.current = null;
            }
        });
        
        // WebSocket 정리
        if (clientRef.current) {
            try {
                clientRef.current.deactivate();
                clientRef.current = null;
            } catch (e) {
                console.warn('WebSocket 정리 중 오류 (무시):', e);
            }
        }
        
        // 좌석 페이지로 이동
        setTimeout(() => {
            const seatParams = new URLSearchParams({
                requestId, sessionId, movieId,
                fromWaitPage: 'true',
                admittedAt: Date.now().toString()
            });
            navigate(`/seats?${seatParams.toString()}`, { replace: true });
        }, 1000);
    }, [requestId, sessionId, movieId, navigate, isAdmitted]);

    // 🔥 개선된 API 폴링 시스템
    const checkStatusAndNavigate = useCallback(async () => {
        if (!movieId || !sessionId || !requestId || isAdmitted) return;
        
        try {
            const status = await checkUserStatus(movieId, sessionId, requestId);
            console.log('🔍 [API 폴링] 사용자 상태:', status);
            
            if (status.status === 'ADMITTED' || status.status === 'ACTIVE') {
                console.log('✅ [API 폴링] 활성 세션 확인! 좌석 페이지로 이동합니다.');
                redirectToSeats();
            } else if (status.status === 'WAITING') {
                let updated = false;
                
                // 순위 업데이트
                if (status.rank !== undefined && status.rank !== myRank) {
                    console.log(`📊 [API 폴링] 순위 업데이트: ${myRank} → ${status.rank}`);
                    setMyRank(status.rank);
                    updated = true;
                }
                
                // 전체 대기자 수 업데이트
                if (status.totalWaiting !== undefined && status.totalWaiting !== totalWaiting) {
                    console.log(`📊 [API 폴링] 총 대기자 업데이트: ${totalWaiting} → ${status.totalWaiting}`);
                    setTotalWaiting(status.totalWaiting);
                    updated = true;
                }
                
                if (updated) {
                    setLastUpdateTime(Date.now());
                    // WebSocket 연결이 없으면 API 폴링 상태임을 표시
                    if (!isConnected) {
                        setStatusMessage(`API를 통해 대기열 순서를 확인하고 있습니다... (${status.rank || myRank}번째)`);
                    }
                }
            } else if (status.status === 'NOT_FOUND') {
                console.error('❌ [API 폴링] 대기열에서 찾을 수 없음');
                alert('대기열에서 찾을 수 없습니다. 다시 시도해주세요.');
                navigate('/movies');
            }
        } catch (error) {
            console.error('❌ API 폴링 상태 확인 실패:', error);
            // 네트워크 오류 시에도 사용자에게 부정적 메시지 표시하지 않음
        }
    }, [movieId, sessionId, requestId, isAdmitted, myRank, totalWaiting, navigate, redirectToSeats, isConnected]);

    // 🔥 새로운 Heartbeat 모니터링 시스템
    const checkHeartbeatHealth = useCallback(() => {
        const now = Date.now();
        const timeSinceLastHeartbeat = now - lastHeartbeat;
        
        // 35초 이상 heartbeat가 없으면 연결 문제로 간주
        if (timeSinceLastHeartbeat > 35000 && isConnected) {
            console.warn('💔 Heartbeat 타임아웃 감지. WebSocket 연결 문제 추정');
            setIsConnected(false);
            
            // API 폴링으로 즉시 전환
            if (!statusCheckIntervalRef.current && !isAdmitted) {
                console.log('🔄 [Heartbeat 타임아웃] API 폴링으로 전환');
                setUsingApiPolling(true);
                statusCheckIntervalRef.current = setInterval(checkStatusAndNavigate, 3000); // 더 빠른 폴링
            }
        }
    }, [lastHeartbeat, isConnected, checkStatusAndNavigate, isAdmitted]);

    // 필수 파라미터 체크
    useEffect(() => {
        if (!requestId || !movieId || !sessionId) {
            console.error('❌ 필수 파라미터 누락');
            alert('세션 정보가 올바르지 않습니다. 영화 선택 페이지로 돌아갑니다.');
            navigate('/movies');
            return;
        }
    }, [requestId, sessionId, movieId, navigate]);

    // 시스템 설정 로드
    useEffect(() => {
        const loadSystemConfig = async () => {
            try {
                const config = await fetchSystemConfig();
                setSystemConfig(config);
            } catch (error) {
                console.error('❌ 시스템 설정 로드 실패:', error);
                setSystemConfig({
                    baseSessionsPerPod: 500,
                    waitTimePerPodSeconds: 10,
                    currentPodCount: 10,
                    error: true
                });
            }
        };

        loadSystemConfig();
        
        // 초기 상태 확인을 1초 후에 한 번 실행
        setTimeout(checkStatusAndNavigate, 1000);
    }, [checkStatusAndNavigate]);

    // 예상 대기 시간 계산
    useEffect(() => {
        if (!systemConfig || myRank === null || myRank <= 0) {
            setEstimatedWaitTime('곧 입장 가능합니다');
            return;
        }
        
        const baseSessionsPerPod = systemConfig.baseSessionsPerPod || 500;
        const waitTimePerPodSeconds = systemConfig.waitTimePerPodSeconds || 10;
        const podsToWait = Math.floor((myRank - 1) / baseSessionsPerPod);
        const waitSeconds = podsToWait * waitTimePerPodSeconds;

        if (waitSeconds === 0) {
            setEstimatedWaitTime('곧 입장 가능합니다');
        } else if (waitSeconds < 60) {
            setEstimatedWaitTime(`약 ${waitSeconds}초`);
        } else {
            const minutes = Math.ceil(waitSeconds / 60);
            setEstimatedWaitTime(`약 ${minutes}분`);
        }
    }, [myRank, systemConfig]);

    // 🔥 개선된 WebSocket 연결 - 부하 상황 대응 강화
    useEffect(() => {
        if (!requestId || !movieId || !sessionId) return;

        console.log('🔌 WebSocket 연결 시작...');

        const client = new Client({
            brokerURL: import.meta.env.VITE_WEBSOCKET_URL,
            
            // 🔥 부하 상황 대응 설정 강화
            reconnectDelay: 2000,        // 재연결 간격을 2초로 단축
            maxReconnectAttempts: 30,    // 재시도 횟수 대폭 증가
            heartbeatIncoming: 20000,    // heartbeat를 20초로 단축 (더 빠른 감지)
            heartbeatOutgoing: 20000,    // heartbeat를 20초로 단축
            connectionTimeout: 10000,    // 연결 타임아웃 10초
            
            // 연결 성공 처리
            onConnect: () => {
                console.log('✅ WebSocket 연결 성공');
                setConnectionStatus('connected');
                setIsConnected(true);
                setReconnectAttempts(0);
                setLastHeartbeat(Date.now());
                setUsingApiPolling(false);
                
                // API 폴링 중지 (WebSocket 연결됨)
                if (statusCheckIntervalRef.current) {
                    console.log('🛑 WebSocket 연결됨. API 폴링 중지');
                    clearInterval(statusCheckIntervalRef.current);
                    statusCheckIntervalRef.current = null;
                }
                
                setStatusMessage('대기열 순서를 실시간으로 확인하고 있습니다.');
                
                // 🔥 대기열 통계 구독 (전체 대기 상황)
                const statsTopicPattern = `/topic/stats/${movieId}`;
                console.log('📊 구독:', statsTopicPattern);
                
                client.subscribe(statsTopicPattern, (message) => {
                    try {
                        const stats = JSON.parse(message.body);
                        console.log('📊 [WebSocket] 대기열 통계 업데이트:', stats);
                        
                        if (stats.totalWaiting !== undefined) {
                            setTotalWaiting(stats.totalWaiting);
                            setLastUpdateTime(Date.now());
                            setLastHeartbeat(Date.now()); // 메시지 수신시 heartbeat 갱신
                        }
                    } catch (error) {
                        console.error('❌ 통계 메시지 파싱 오류:', error);
                    }
                });
                
                // 🔥 개인 입장 알림 구독
                const admitTopicPattern = `/topic/admit/${requestId}`;
                console.log('🎫 구독:', admitTopicPattern);
                
                client.subscribe(admitTopicPattern, (message) => {
                    try {
                        const data = JSON.parse(message.body);
                        console.log('🎫 [WebSocket] 입장 알림:', data);
                        
                        if (data.status === 'ADMITTED') {
                            console.log('🎉 WebSocket으로 입장 허가 받음!');
                            redirectToSeats();
                        } else if (data.rank !== undefined) {
                            // 순위 업데이트도 받을 수 있음
                            setMyRank(data.rank);
                            setLastUpdateTime(Date.now());
                            setLastHeartbeat(Date.now());
                        }
                    } catch (error) {
                        console.error('❌ 입장 알림 메시지 파싱 오류:', error);
                    }
                });
            },
            
            // 🔥 연결 종료 처리 개선
            onWebSocketClose: (event) => {
                console.warn('🔌 WebSocket 연결 종료:', event?.reason || 'Unknown');
                setIsConnected(false);
                setReconnectAttempts(prev => prev + 1);
                
                // 즉시 API 폴링으로 전환 (부하 상황에서 빠른 복구)
                if (!statusCheckIntervalRef.current && !isAdmitted) {
                    console.log('🔗 [WebSocket 끊김] 즉시 API 폴링으로 전환');
                    setUsingApiPolling(true);
                    statusCheckIntervalRef.current = setInterval(checkStatusAndNavigate, 3000);
                }
            },
            
            // 오류 처리
            onStompError: (frame) => {
                console.error('❌ STOMP 오류:', frame);
                setIsConnected(false);
                
                // STOMP 오류 시에도 API 폴링으로 전환
                if (!statusCheckIntervalRef.current && !isAdmitted) {
                    console.log('🔗 [STOMP 오류] API 폴링으로 전환');
                    setUsingApiPolling(true);
                    statusCheckIntervalRef.current = setInterval(checkStatusAndNavigate, 3000);
                }
            },
            
            onWebSocketError: (event) => {
                console.error('❌ WebSocket 오류:', event);
                setIsConnected(false);
            }
        });

        client.activate();
        clientRef.current = client;

        return () => {
            console.log('🧹 WaitPage 정리 시작');
            
            // 모든 인터벌 정리
            [statusCheckIntervalRef, heartbeatCheckIntervalRef, reconnectTimeoutRef].forEach(ref => {
                if (ref.current) {
                    clearInterval(ref.current);
                    clearTimeout(ref.current);
                    ref.current = null;
                }
            });
            
            // WebSocket 정리
            if (client) {
                try {
                    client.deactivate();
                } catch (e) {
                    console.warn('WebSocket 정리 중 오류:', e);
                }
            }
        };
    }, [requestId, movieId, sessionId, navigate, checkStatusAndNavigate, redirectToSeats, isAdmitted]);

    // 🔥 Heartbeat 건강성 모니터링
    useEffect(() => {
        heartbeatCheckIntervalRef.current = setInterval(checkHeartbeatHealth, 5000); // 5초마다 체크
        
        return () => {
            if (heartbeatCheckIntervalRef.current) {
                clearInterval(heartbeatCheckIntervalRef.current);
            }
        };
    }, [checkHeartbeatHealth]);

    // 🔥 API 폴링 백업 시스템 (WebSocket 실패시)
    useEffect(() => {
        // WebSocket 연결 실패 시 자동으로 API 폴링 시작
        if (!isConnected && !isAdmitted && reconnectAttempts >= 3) {
            if (!statusCheckIntervalRef.current) {
                console.log('🔄 [재연결 실패] API 폴링 백업 시스템 활성화');
                setUsingApiPolling(true);
                statusCheckIntervalRef.current = setInterval(checkStatusAndNavigate, 3000);
            }
        }
    }, [isConnected, isAdmitted, reconnectAttempts, checkStatusAndNavigate]);

    return (
        <div className={styles.container}>
            <div className={styles.waitCard}>
                <h1 className={styles.title}>{movieTitle} 대기열</h1>
                
                {/* 대기 순번 표시 */}
                <div className={styles.rankSection}>
                    <div className={styles.currentRank}>
                        <span className={styles.rankNumber}>{myRank?.toLocaleString() || '-'}</span>
                        <span className={styles.rankLabel}>번째</span>
                    </div>
                    <div className={styles.waitingInfo}>
                        <p>전체 대기: <strong>{totalWaiting?.toLocaleString() || '-'}명</strong></p>
                        <p>예상 대기시간: <strong>{estimatedWaitTime}</strong></p>
                    </div>
                </div>

                {/* 상태 메시지 */}
                <div className={styles.statusSection}>
                    <div className={styles.statusIndicator}>
                        {/* 부하 상황에서도 긍정적인 메시지 유지 */}
                        <span className={`${styles.indicator} ${isConnected ? styles.connected : styles.polling}`}>
                            {isConnected ? '🔗' : '🔄'}
                        </span>
                        <span>{statusMessage}</span>
                    </div>
                    
                    {usingApiPolling && (
                        <div className={styles.pollingNotice}>
                            <small>📡 서버 부하로 인해 API 모드로 동작 중입니다</small>
                        </div>
                    )}
                </div>

                {/* 마지막 업데이트 시간 */}
                <div className={styles.lastUpdate}>
                    <small>
                        마지막 업데이트: {new Date(lastUpdateTime).toLocaleTimeString()}
                        {isConnected ? ' (실시간)' : ' (API)'}
                    </small>
                </div>

                {/* 개발자 디버깅 정보 (개발 환경에서만) */}
                {import.meta.env.DEV && (
                    <details className={styles.debugInfo}>
                        <summary>🔧 디버깅 정보</summary>
                        <div className={styles.debugContent}>
                            <div><strong>연결 상태:</strong> {isConnected ? '🟢 WebSocket' : (usingApiPolling ? '🟡 API 폴링' : '🔴 끊김')}</div>
                            <div><strong>재연결 시도:</strong> {reconnectAttempts}회</div>
                            <div><strong>순위:</strong> {myRank || 'N/A'}</div>
                            <div><strong>대기자:</strong> {totalWaiting || 'N/A'}</div>
                            <div><strong>입장여부:</strong> {isAdmitted ? 'YES' : 'NO'}</div>
                            <div><strong>RequestID:</strong> {requestId?.substring(0, 8) || 'N/A'}...</div>
                            <div><strong>마지막 Heartbeat:</strong> {Math.floor((Date.now() - lastHeartbeat) / 1000)}초 전</div>
                        </div>
                    </details>
                )}
            </div>
        </div>
    );
}