import React from 'react';
import { useLocation, Link } from 'react-router-dom';
import { useContext } from 'react';
import { AppContext } from '../context/AppContext';
import styles from './SuccessPage.module.css';

export default function SuccessPage() {
  const location = useLocation();
  const { state } = useContext(AppContext);
  const transactionId = location.state?.transactionId;
  
  // 최근 예매 정보 가져오기
  const latestBooking = state.bookingHistory[state.bookingHistory.length - 1];
  
  if (!latestBooking || !transactionId) {
    return (
      <div className={styles.container}>
        <div className={styles.error}>
          <h1>❌ 예매 정보를 찾을 수 없습니다</h1>
          <Link to="/movies" className={styles.homeButton}>
            영화 선택하러 가기
          </Link>
        </div>
      </div>
    );
  }
  
  return (
    <div className={styles.container}>
      <div className={styles.successCard}>
        <div className={styles.successIcon}>
          <div className={styles.checkmark}>✓</div>
        </div>
        
        <h1>🎉 예매가 완료되었습니다!</h1>
        <p className={styles.subtitle}>예매번호를 안전하게 보관해주세요</p>
        
        <div className={styles.bookingDetails}>
          <div className={styles.ticketInfo}>
            <h2>🎫 예매 정보</h2>
            <div className={styles.infoGrid}>
              <div className={styles.infoItem}>
                <span className={styles.label}>예매번호</span>
                <span className={styles.value}>{transactionId}</span>
              </div>
              <div className={styles.infoItem}>
                <span className={styles.label}>영화</span>
                <span className={styles.value}>{latestBooking.movie.title}</span>
              </div>
              <div className={styles.infoItem}>
                <span className={styles.label}>극장</span>
                <span className={styles.value}>{latestBooking.theater.name}</span>
              </div>
              <div className={styles.infoItem}>
                <span className={styles.label}>상영시간</span>
                <span className={styles.value}>{latestBooking.time}</span>
              </div>
              <div className={styles.infoItem}>
                <span className={styles.label}>좌석</span>
                <span className={styles.value}>{latestBooking.seats.join(', ')}</span>
              </div>
              <div className={styles.infoItem}>
                <span className={styles.label}>결제금액</span>
                <span className={styles.value}>{latestBooking.totalPrice.toLocaleString()}원</span>
              </div>
              {latestBooking.usedCoupon && (
                <div className={styles.infoItem}>
                  <span className={styles.label}>사용된 쿠폰</span>
                  <span className={styles.value}>2,000원 할인쿠폰</span>
                </div>
              )}
            </div>
          </div>
          
          <div className={styles.qrCode}>
            <div className={styles.qrPlaceholder}>
              <div className={styles.qrPattern}>
                {Array.from({ length: 9 }, (_, i) => (
                  <div key={i} className={styles.qrRow}>
                    {Array.from({ length: 9 }, (_, j) => (
                      <div 
                        key={j} 
                        className={`${styles.qrDot} ${Math.random() > 0.5 ? styles.filled : ''}`} 
                      />
                    ))}
                  </div>
                ))}
              </div>
            </div>
            <p>영화관 입장 시 제시해주세요</p>
          </div>
        </div>
        
        <div className={styles.actions}>
          <Link to="/history" className={styles.historyButton}>
            예매 내역 확인
          </Link>
          <Link to="/movies" className={styles.newBookingButton}>
            새로운 예매하기
          </Link>
        </div>
        
        <div className={styles.notice}>
          <h3>📢 안내사항</h3>
          <ul>
            <li>상영 30분 전까지 입장해주세요</li>
            <li>예매 취소는 상영 20분 전까지 가능합니다</li>
            <li>예매번호나 QR코드로 입장이 가능합니다</li>
            <li>분실 시 본인 확인을 통해 재발급 가능합니다</li>
          </ul>
        </div>
      </div>
    </div>
  );
}
