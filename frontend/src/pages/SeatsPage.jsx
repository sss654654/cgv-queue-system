import React, { useContext, useState, useEffect, useRef } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { AppContext } from '../context/AppContext';
import { leaveMovieQueue, fetchSystemConfig  } from '../api/api';
import styles from './SeatsPage.module.css';

export default function SeatsPage() {
  const { state, dispatch } = useContext(AppContext);
  const navigate = useNavigate();
  const location = useLocation();
  const timerRef = useRef(null);

  // 상태 관리
  const [seats, setSeats] = useState([]);
  const [selectedSeats, setSelectedSeats] = useState([]);
  const [timeLeft, setTimeLeft] = useState(30); // 30초 제한
  const [isTimeUp, setIsTimeUp] = useState(false);
  const [movieInfo, setMovieInfo] = useState(null);
  const [isLoading, setIsLoading] = useState(false);

  // URL에서 예매 정보 가져오기
  const urlParams = new URLSearchParams(location.search);
  const requestId = urlParams.get('requestId');
  const sessionId = urlParams.get('sessionId');
  const movieId = urlParams.get('movieId');

  console.log('좌석 선택 페이지 초기화:', { requestId, sessionId, movieId });

  // 좌석 데이터 생성 (실제로는 API에서 받아올 데이터)
  const generateSeats = () => {
    const rows = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J']; // 10줄로 확장
    const seatData = [];
    
    rows.forEach((row, rowIndex) => {
      // 앞쪽(A,B)는 12석, 중간(C-F)는 14석, 뒤쪽(G-J)는 16석
      let seatsInRow;
      if (rowIndex <= 1) seatsInRow = 12; // A, B행
      else if (rowIndex <= 5) seatsInRow = 14; // C-F행  
      else seatsInRow = 16; // G-J행
      
      for (let i = 1; i <= seatsInRow; i++) {
        const seatId = `${row}${i}`;
        let type = 'available';
        let price = 12000; // 기본 가격
        
        // 랜덤하게 일부 좌석을 예약됨으로 설정
        if (Math.random() < 0.25) {
          type = 'occupied';
        }
        
        // VIP석 (뒷줄 중앙 부분)
        if ((row === 'H' || row === 'I' || row === 'J') && 
            i >= Math.floor(seatsInRow/2) - 2 && i <= Math.floor(seatsInRow/2) + 3) {
          type = type === 'occupied' ? 'occupied' : 'vip';
          price = 18000;
        }
        
        // 커플석 (각 행 양 끝)
        if ((i === 1 || i === 2 || i === seatsInRow - 1 || i === seatsInRow) && Math.random() < 0.3) {
          type = type === 'occupied' ? 'occupied' : 'couple';
          price = 15000;
        }
        
        // 장애인석 (앞줄 양옆)
        if ((row === 'A' || row === 'B') && (i === 1 || i === seatsInRow)) {
          type = type === 'occupied' ? 'occupied' : 'disabled';
          price = 12000;
        }
        
        seatData.push({
          id: seatId,
          row,
          number: i,
          type,
          price
        });
      }
    });
    
    return seatData;
  };

  // ✅ 영화 정보 및 시스템 설정 로드 (useEffect 통합 및 수정)
  useEffect(() => {
    const loadPageData = async () => {
      setIsLoading(true);
      try {
        // 두 개의 API를 동시에 호출하여 성능을 최적화합니다.
        const [configResponse, moviesResponse] = await Promise.all([
          fetchSystemConfig(),
          fetch(`${import.meta.env.VITE_API_BASE}/api/movies`, { credentials: 'include' })
        ]);
        
        // 1. 시스템 설정에서 타이머 시간 설정
        if (configResponse) { // fetchSystemConfig는 이미 JSON을 반환한다고 가정
          console.log('시스템 설정 로드 완료:', configResponse);
          // API에서 받은 sessionTimeoutSeconds 값으로 타이머 상태를 업데이트합니다.
          setTimeLeft(configResponse.sessionTimeoutSeconds);
        }

        // 2. 영화 정보 설정
        if (moviesResponse.ok) {
          const movies = await moviesResponse.json();
          const movie = movies.find(m => m.movieId === movieId);
          setMovieInfo(movie || { title: '영화 정보 없음', movieId });
        } else {
           setMovieInfo({ title: '영화 정보 없음', movieId });
        }

      } catch (error) {
        console.error('페이지 데이터 로드 실패:', error);
        setMovieInfo({ title: '영화 정보 없음', movieId });
        // 에러 발생 시 기본값 30초로 타이머가 동작합니다.
      } finally {
        setIsLoading(false);
      }
    };

    setSeats(generateSeats());
    if (movieId) {
      loadPageData();
    }
  }, [movieId]); // movieId가 변경될 때만 실행

  // 30초 타이머
  useEffect(() => {
    if (timeLeft <= 0) {
      return;
    }

    timerRef.current = setInterval(() => {
      setTimeLeft(prev => {
        if (prev <= 1) {
          setIsTimeUp(true);
          clearInterval(timerRef.current);
          handleSessionTimeout();
          return 0;
        }
        return prev - 1;
      });
    }, 1000);

    return () => {
      if (timerRef.current) {
        clearInterval(timerRef.current);
      }
    };
  }, [timeLeft]);

  // 세션 타임아웃 처리
  const handleSessionTimeout = async () => {
    alert('좌석 선택 시간이 초과되었습니다. 영화 선택 화면으로 돌아갑니다.');
    
    try {
      await leaveMovieQueue(movieId, sessionId, requestId);
      console.log('타임아웃으로 인한 퇴장 처리 완료');
    } catch (error) {
      console.error('퇴장 처리 실패:', error);
    }
    
    navigate('/movies');
  };

  // 좌석 선택 처리
  const handleSeatClick = (seat) => {
    if (seat.type === 'occupied' || isTimeUp || isLoading) return;

    setSelectedSeats(prev => {
      if (prev.find(s => s.id === seat.id)) {
        // 이미 선택된 좌석이면 제거
        return prev.filter(s => s.id !== seat.id);
      } else {
        // 최대 4개까지 선택 가능
        if (prev.length >= 4) {
          alert('최대 4개 좌석까지 선택 가능합니다.');
          return prev;
        }
        return [...prev, seat];
      }
    });
  };

  // 결제 진행
  const handlePayment = async () => {
    if (selectedSeats.length === 0) {
      alert('좌석을 선택해주세요.');
      return;
    }

    setIsLoading(true);

    try {
      // 선택된 좌석 정보 저장
      dispatch({
        type: 'SELECT_SEATS',
        payload: {
          seats: selectedSeats,
          totalPrice: selectedSeats.reduce((sum, seat) => sum + seat.price, 0),
          movieInfo,
          requestId,
          sessionId
        }
      });

      // 백엔드에 좌석 예약 API 호출 (실제로는 구현 필요)
      console.log('좌석 예약 처리:', selectedSeats);
      
      navigate('/payment');
    } catch (error) {
      console.error('결제 진행 실패:', error);
      alert('오류가 발생했습니다. 다시 시도해주세요.');
    } finally {
      setIsLoading(false);
    }
  };

  // 좌석 클래스명 결정
  const getSeatClassName = (seat) => {
    let className = styles.seat;
    
    if (selectedSeats.find(s => s.id === seat.id)) {
      className += ` ${styles.selected}`;
    } else {
      switch (seat.type) {
        case 'occupied':
          className += ` ${styles.occupied}`;
          break;
        case 'vip':
          className += ` ${styles.vip}`;
          break;
        case 'couple':
          className += ` ${styles.couple}`;
          break;
        case 'disabled':
          className += ` ${styles.disabled}`;
          break;
        default:
          className += ` ${styles.available}`;
      }
    }
    
    return className;
  };

  const totalPrice = selectedSeats.reduce((sum, seat) => sum + seat.price, 0);
  const formatTime = (seconds) => `${Math.floor(seconds / 60)}:${(seconds % 60).toString().padStart(2, '0')}`;

  // 세션 정보 확인
  if (!requestId || !sessionId || !movieId) {
    return (
      <div className={styles.container}>
        <div className={styles.errorMessage}>
          <h2>세션 정보가 없습니다</h2>
          <p>영화 선택 페이지로 돌아가 다시 시도해주세요.</p>
          <button onClick={() => navigate('/movies')} className={styles.backButton}>
            영화 선택으로 돌아가기
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.container}>
      {/* 상단 헤더 */}
      <div className={styles.header}>
        <div className={styles.headerContent}>
          <div className={styles.movieTitle}>
            <h1>{movieInfo?.title || '영화 제목'}</h1>
            <div className={styles.movieMeta}>
              <span>{movieInfo?.genre}</span>
              <span>{movieInfo?.durationInMinutes}분</span>
              <span>{movieInfo?.ageRating}</span>
            </div>
          </div>
          
          
        </div>
      </div>

      <div className={styles.theaterContainer}>
        {/* 스크린 (상단에 크게 배치) */}
        <div className={styles.screenSection}>
          <div className={styles.screen}>
            <div className={styles.screenText}>S C R E E N</div>
            <div className={styles.screenGlow}></div>
          </div>
        </div>

        {/* 메인 콘텐츠 영역 */}
        <div className={styles.mainContent}>
          {/* 좌석 맵 */}
          <div className={styles.seatMapSection}>
            <div className={styles.seatMap}>
              {['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J'].map((row, rowIndex) => {
                const rowSeats = seats.filter(seat => seat.row === row);
                const seatsInRow = rowSeats.length;
                
                return (
                  <div key={row} className={styles.seatRow} data-row={row}>
                    {/* 왼쪽 행 번호 */}
                    <div className={styles.rowLabel}>{row}</div>
                    
                    {/* 좌석들 */}
                    <div className={styles.seatsContainer} style={{
                      gridTemplateColumns: `repeat(${seatsInRow}, 1fr)`,
                      gap: seatsInRow > 14 ? '4px' : '6px' // 좌석이 많은 행은 간격 줄임
                    }}>
                      {rowSeats.map((seat, seatIndex) => {
                        // 중앙 통로를 위한 여백 추가
                        const isLeftOfCenter = seatIndex < Math.floor(seatsInRow / 2) - 1;
                        const isRightOfCenter = seatIndex >= Math.floor(seatsInRow / 2) + 1;
                        const needsGap = seatIndex === Math.floor(seatsInRow / 2) - 1;
                        
                        return (
                          <button
                            key={seat.id}
                            className={getSeatClassName(seat)}
                            onClick={() => handleSeatClick(seat)}
                            disabled={seat.type === 'occupied' || isTimeUp}
                            title={`${seat.id} - ${seat.price.toLocaleString()}원`}
                            style={{
                              marginRight: needsGap ? '12px' : '0'
                            }}
                          >
                            <span className={styles.seatNumber}>{seat.number}</span>
                            {seat.type === 'vip' && <span className={styles.seatIcon}>★</span>}
                            {seat.type === 'couple' && <span className={styles.seatIcon}>♥</span>}
                            {seat.type === 'disabled' && <span className={styles.seatIcon}>♿</span>}
                          </button>
                        );
                      })}
                    </div>
                    
                    {/* 오른쪽 행 번호 */}
                    <div className={styles.rowLabel}>{row}</div>
                  </div>
                );
              })}
            </div>

            {/* 범례 */}
            <div className={styles.legend}>
              <div className={styles.legendItem}>
                <div className={`${styles.legendSeat} ${styles.available}`}></div>
                <span>선택가능</span>
                <span className={styles.legendPrice}>12,000원</span>
              </div>
              <div className={styles.legendItem}>
                <div className={`${styles.legendSeat} ${styles.selected}`}></div>
                <span>선택됨</span>
              </div>
              <div className={styles.legendItem}>
                <div className={`${styles.legendSeat} ${styles.occupied}`}></div>
                <span>예약완료</span>
              </div>
              <div className={styles.legendItem}>
                <div className={`${styles.legendSeat} ${styles.vip}`}>★</div>
                <span>VIP석</span>
                <span className={styles.legendPrice}>18,000원</span>
              </div>
              <div className={styles.legendItem}>
                <div className={`${styles.legendSeat} ${styles.couple}`}>♥</div>
                <span>커플석</span>
                <span className={styles.legendPrice}>15,000원</span>
              </div>
              <div className={styles.legendItem}>
                <div className={`${styles.legendSeat} ${styles.disabled}`}>♿</div>
                <span>장애인석</span>
                <span className={styles.legendPrice}>12,000원</span>
              </div>
            </div>
          </div>

          {/* 선택 정보 사이드바 */}
          <div className={styles.sidebar}>
            <div className={styles.selectionPanel}>
              <h3>선택한 좌석</h3>
              
              {selectedSeats.length > 0 ? (
                <div className={styles.selectedSeatsList}>
                  {selectedSeats.map(seat => (
                    <div key={seat.id} className={styles.selectedSeatItem}>
                      <div className={styles.seatInfo}>
                        <span className={styles.seatId}>{seat.id}석</span>
                        <span className={styles.seatType}>
                          {seat.type === 'vip' ? 'VIP' : 
                           seat.type === 'couple' ? '커플' : 
                           seat.type === 'disabled' ? '장애인' : '일반'}
                        </span>
                      </div>
                      <span className={styles.seatPrice}>
                        {seat.price.toLocaleString()}원
                      </span>
                    </div>
                  ))}
                  
                  <div className={styles.totalSection}>
                    <div className={styles.totalPrice}>
                      <span>총 금액</span>
                      <strong>{totalPrice.toLocaleString()}원</strong>
                    </div>
                    <div className={styles.seatCount}>
                      {selectedSeats.length}석 선택
                    </div>
                  </div>
                </div>
              ) : (
                <div className={styles.noSeatsSelected}>
                  <div className={styles.noSeatsIcon}>🎫</div>
                  <p>좌석을 선택해주세요</p>
                  <small>최대 4석까지 선택 가능합니다</small>
                </div>
              )}

              <button
                className={styles.paymentButton}
                onClick={handlePayment}
                disabled={selectedSeats.length === 0 || isTimeUp || isLoading}
              >
                {isLoading ? '처리 중...' : 
                 selectedSeats.length === 0 ? '좌석을 선택해주세요' : 
                 `${selectedSeats.length}석 결제하기`}
              </button>

              <div className={styles.notice}>
                <h4>안내사항</h4>
                <ul>
                  <li>제한시간 내에 좌석을 선택하고 결제해주세요</li>
                  <li>최대 4개 좌석까지 선택 가능합니다</li>
                  <li>VIP석과 커플석은 추가 요금이 있습니다</li>
                  <li>선택한 좌석은 5분간 임시 예약됩니다</li>
                </ul>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}