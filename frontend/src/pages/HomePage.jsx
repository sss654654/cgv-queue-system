import React, { useContext, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AppContext } from '../context/AppContext';
import { MOVIES } from '../data/movies';
import styles from './HomePage.module.css';

export default function HomePage() {
  const { state } = useContext(AppContext);
  const navigate = useNavigate();

  const { userCoupon, totalIssued, maxCoupons } = state.coupon;
  const { bookingHistory } = state;

  const featuredMovies = MOVIES.slice(0, 3);
  const remainingCoupons = maxCoupons - totalIssued;
  const couponProgress = (totalIssued / maxCoupons) * 100;

  // ====== [추가] 리전 표시를 위한 상태/로직 ======
  const [region, setRegion] = useState(null); // ex) "ap-northeast-2"
  const [regionLabel, setRegionLabel] = useState('알 수 없음');
  const API_BASE = import.meta.env.VITE_API_BASE;
  useEffect(() => {
    let cancelled = false;

    const mapRegionToLabel = (code) => {
      if (!code) return '알 수 없음';
      switch (code) {
        case 'ap-northeast-2': return '서울';
        case 'ap-northeast-1': return '도쿄';
        default: return code; // 모르는 리전은 코드 그대로 노출
      }
    };

    (async () => {
      try {
        const res = await fetch(`${API_BASE}/system/region`, { credentials: 'include' });
        if (!res.ok) throw new Error('region api error');
        const data = await res.json();
        if (cancelled) return;
        setRegion(data.region);
        setRegionLabel(mapRegionToLabel(data.region));
      } catch (e) {
        if (cancelled) return;
        setRegion(null);
        setRegionLabel('알 수 없음');
      }
    })();

    return () => { cancelled = true; };
  }, []);
  // ==========================================

  return (
    <div className={styles.container}>
      {/* Hero Section */}
      <section className={styles.hero}>
        <div className={styles.heroContent}>
          {/* ====== [변경] 리전 동적 표시 (기존 "리전 : 서울" 대체) ====== */}
          <div className={styles.regionBadge} style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: '8px',
            padding: '6px 10px',
            borderRadius: '999px',
            background: '#F3F4F6',
            color: '#111827',
            fontSize: '0.9rem',
            marginBottom: '8px',
            border: '1px solid #E5E7EB'
          }}>
            <span>리전</span>
            <strong>
              {region ? `${regionLabel} (${region})` : regionLabel}
            </strong>
          </div>
          {/* ================================================ */}

          <h1 className={styles.heroTitle}>
            🎬 <span className={styles.brandName}>CGV</span>에서
            <br />
            <span className={styles.highlight}>특별한 영화 경험</span>을
          </h1>
          <p className={styles.heroSubtitle}>
            최신 영화부터 할인 혜택까지, 모든 것이 한 곳에
          </p>
          <div className={styles.heroStats}>
            <div className={styles.stat}>
              <span className={styles.statNumber}>{MOVIES.length}</span>
              <span className={styles.statLabel}>현재 상영작</span>
            </div>
            <div className={styles.stat}>
              <span className={styles.statNumber}>{totalIssued.toLocaleString()}</span>
              <span className={styles.statLabel}>발급된 쿠폰</span>
            </div>
            <div className={styles.stat}>
              <span className={styles.statNumber}>{bookingHistory.length}</span>
              <span className={styles.statLabel}>나의 예매</span>
            </div>
          </div>
        </div>
        <div className={styles.heroVisual}>
          <div className={styles.movieReel}>🎞️</div>
        </div>
      </section>

      {/* Main Services */}
      <section className={styles.services}>
        <h2 className={styles.sectionTitle}>🌟 주요 서비스</h2>

        <div className={styles.serviceGrid}>
          {/* 영화 예매 카드 */}
          <ServiceCard
            icon="🎬"
            title="영화 예매"
            description="최신 영화를 편리하게 예매하세요"
            color="primary"
            onClick={() => navigate('/movies')}
          >
            <div className={styles.moviePreview}>
              <h4>지금 상영 중인 인기작</h4>
              <div className={styles.movieList}>
                {featuredMovies.map(movie => (
                  <div key={movie.id} className={styles.movieItem}>
                    <img src={movie.poster} alt={movie.title} />
                    <span>{movie.title}</span>
                  </div>
                ))}
              </div>
            </div>
          </ServiceCard>

          {/* 쿠폰 이벤트 카드 */}
          <ServiceCard
            icon="🎫"
            title="할인 쿠폰"
            description={userCoupon ? "쿠폰 보유 중" : "선착순 450만장 한정!"}
            color="secondary"
            onClick={() => navigate('/coupon')}
            badge={userCoupon ? "보유중" : `${remainingCoupons.toLocaleString()}장 남음`}
          >
            <div className={styles.couponPreview}>
              <div className={styles.couponProgress}>
                <div className={styles.couponProgressBar}>
                  <div
                    className={styles.couponProgressFill}
                    style={{ width: `${couponProgress}%` }}
                  />
                </div>
                <span>{couponProgress.toFixed(1)}% 발급완료</span>
              </div>
              {userCoupon ? (
                <div className={styles.ownedCoupon}>
                  <div className={styles.couponIcon}>🎫</div>
                  <span>2,000원 할인쿠폰 보유</span>
                </div>
              ) : (
                <div className={styles.couponOffer}>
                  <span className={styles.couponAmount}>2,000원</span>
                  <span className={styles.couponText}>즉시 할인</span>
                </div>
              )}
            </div>
          </ServiceCard>
        </div>
      </section>

      {/* 최근 예매 내역 (있는 경우만) */}
      {bookingHistory.length > 0 && (
        <section className={styles.recentBookings}>
          <h2 className={styles.sectionTitle}>📋 최근 예매</h2>
          <div className={styles.bookingCards}>
            {bookingHistory.slice(-2).reverse().map(booking => (
              <div key={booking.id} className={styles.bookingCard}>
                <img src={booking.movie.poster} alt={booking.movie.title} />
                <div className={styles.bookingInfo}>
                  <h4>{booking.movie.title}</h4>
                  <p>{booking.theater.name}</p>
                  <p>{booking.time} • {booking.seats.join(', ')}</p>
                </div>
                <div className={styles.bookingPrice}>
                  {booking.totalPrice.toLocaleString()}원
                </div>
              </div>
            ))}
          </div>
          <Link to="/history" className={styles.viewAllBookings}>
            전체 예매 내역 보기 →
          </Link>
        </section>
      )}

      {/* Quick Actions */}
      <section className={styles.quickActions}>
        <h2 className={styles.sectionTitle}>⚡ 빠른 실행</h2>
        <div className={styles.actionGrid}>
          <QuickAction
            icon="🎭"
            label="영화 보러가기"
            to="/movies"
          />
          <QuickAction
            icon="🎫"
            label="쿠폰 받기"
            to="/coupon"
            badge={userCoupon ? null : "NEW"}
          />
          <QuickAction
            icon="📋"
            label="예매 확인"
            to="/history"
            badge={bookingHistory.length > 0 ? bookingHistory.length.toString() : null}
          />
        </div>
      </section>
    </div>
  );
}

function ServiceCard({ icon, title, description, color, children, onClick, badge }) {
  return (
    <div
      className={`${styles.serviceCard} ${styles[color]}`}
      onClick={onClick}
    >
      <div className={styles.cardHeader}>
        <div className={styles.cardIcon}>{icon}</div>
        <div className={styles.cardTitleArea}>
          <h3 className={styles.cardTitle}>{title}</h3>
          <p className={styles.cardDescription}>{description}</p>
        </div>
        {badge && (
          <div className={styles.cardBadge}>
            {badge}
          </div>
        )}
      </div>
      <div className={styles.cardContent}>
        {children}
      </div>
      <div className={styles.cardAction}>
        <span>시작하기 →</span>
      </div>
    </div>
  );
}

function QuickAction({ icon, label, to, badge }) {
  return (
    <Link to={to} className={styles.quickAction}>
      <div className={styles.quickActionIcon}>{icon}</div>
      <span className={styles.quickActionLabel}>{label}</span>
      {badge && (
        <span className={styles.quickActionBadge}>{badge}</span>
      )}
    </Link>
  );
}
