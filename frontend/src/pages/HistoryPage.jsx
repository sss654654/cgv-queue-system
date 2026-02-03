import React from 'react';
import { useContext, useState } from 'react';
import { Link } from 'react-router-dom';
import { AppContext } from '../context/AppContext';
import styles from './HistoryPage.module.css';

export default function HistoryPage() {
  const { state } = useContext(AppContext);
  const { bookingHistory } = state;
  const [expandedBooking, setExpandedBooking] = useState(null);
  
  const toggleExpanded = (bookingId) => {
    setExpandedBooking(expandedBooking === bookingId ? null : bookingId);
  };
  
  if (bookingHistory.length === 0) {
    return (
      <div className={styles.container}>
        <div className={styles.header}>
          <h1>📋 예매 내역</h1>
        </div>
        
        <div className={styles.emptyState}>
          <div className={styles.emptyIcon}>🎬</div>
          <h2>예매 내역이 없습니다</h2>
          <p>첫 번째 영화를 예매해보세요!</p>
          <Link to="/movies" className={styles.bookButton}>
            영화 예매하기
          </Link>
        </div>
      </div>
    );
  }
  
  // 최신 예매 순으로 정렬
  const sortedHistory = [...bookingHistory].sort((a, b) => 
    new Date(b.bookedAt) - new Date(a.bookedAt)
  );
  
  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <h1>📋 예매 내역</h1>
        <div className={styles.stats}>
          총 {bookingHistory.length}건의 예매
        </div>
      </div>
      
      <div className={styles.bookingList}>
        {sortedHistory.map((booking) => (
          <BookingCard
            key={booking.id}
            booking={booking}
            isExpanded={expandedBooking === booking.id}
            onToggleExpanded={() => toggleExpanded(booking.id)}
          />
        ))}
      </div>
      
      <div className={styles.actions}>
        <Link to="/movies" className={styles.newBookingButton}>
          새로운 예매하기
        </Link>
      </div>
    </div>
  );
}

function BookingCard({ booking, isExpanded, onToggleExpanded }) {
  const bookedDate = new Date(booking.bookedAt);
  
  return (
    <div className={styles.bookingCard}>
      <div className={styles.cardHeader} onClick={onToggleExpanded}>
        <div className={styles.movieInfo}>
          <div className={styles.moviePoster}>
            <img src={booking.movie.poster} alt={booking.movie.title} />
          </div>
          <div className={styles.basicInfo}>
            <h3>{booking.movie.title}</h3>
            <p>{booking.theater.name}</p>
            <p>{booking.time}</p>
            <p className={styles.bookingDate}>
              예매일: {bookedDate.toLocaleDateString('ko-KR')}
            </p>
          </div>
        </div>
        <div className={styles.priceInfo}>
          <span className={styles.price}>
            {booking.totalPrice.toLocaleString()}원
          </span>
          <span className={styles.seats}>
            {booking.seats.length}석
          </span>
          <button className={styles.expandButton}>
            {isExpanded ? '▲ 접기' : '▼ 자세히'}
          </button>
        </div>
      </div>
      
      {isExpanded && (
        <div className={styles.expandedContent}>
          <div className={styles.detailsGrid}>
            <div className={styles.detailItem}>
              <span className={styles.detailLabel}>예매번호</span>
              <span className={styles.detailValue}>TXN{booking.id}</span>
            </div>
            <div className={styles.detailItem}>
              <span className={styles.detailLabel}>선택좌석</span>
              <span className={styles.detailValue}>{booking.seats.join(', ')}</span>
            </div>
            <div className={styles.detailItem}>
              <span className={styles.detailLabel}>영화 정보</span>
              <span className={styles.detailValue}>
                {booking.movie.genre} • {booking.movie.duration}분 • {booking.movie.rating}
              </span>
            </div>
            {booking.usedCoupon && (
              <div className={styles.detailItem}>
                <span className={styles.detailLabel}>할인</span>
                <span className={styles.detailValue}>쿠폰 2,000원 할인 적용</span>
              </div>
            )}
            <div className={styles.detailItem}>
              <span className={styles.detailLabel}>결제일시</span>
              <span className={styles.detailValue}>
                {bookedDate.toLocaleString('ko-KR')}
              </span>
            </div>
          </div>
          
          <div className={styles.qrSection}>
            <div className={styles.miniQr}>
              <div className={styles.qrPattern}>
                {Array.from({ length: 6 }, (_, i) => (
                  <div key={i} className={styles.qrRow}>
                    {Array.from({ length: 6 }, (_, j) => (
                      <div 
                        key={j} 
                        className={`${styles.qrDot} ${Math.random() > 0.5 ? styles.filled : ''}`} 
                      />
                    ))}
                  </div>
                ))}
              </div>
            </div>
            <p>입장용 QR코드</p>
          </div>
        </div>
      )}
    </div>
  );
}