import React from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { useContext } from 'react';
import { AppContext } from '../../context/AppContext';
import styles from './Navigation.module.css';

export default function Navigation() {
  const { state } = useContext(AppContext);
  const location = useLocation();
  const { userCoupon } = state.coupon;
  const { bookingHistory } = state;
  
  const navItems = [
    { 
      path: '/', 
      label: '홈', 
      icon: '🏠'
    },
    { 
      path: '/coupon', 
      label: '쿠폰받기', 
      icon: '🎫',
      badge: userCoupon ? '1' : null
    },
    { 
      path: '/movies', 
      label: '영화예매', 
      icon: '🎬' 
    },
    { 
      path: '/history', 
      label: '예매내역', 
      icon: '📋',
      badge: bookingHistory.length > 0 ? bookingHistory.length.toString() : null
    }
  ];
  
  return (
    <nav className={styles.navigation}>
      <div className={styles.navContainer}>
        <div className={styles.logo}>
          <NavLink to="/" className={styles.logoLink}>
            🎭 CGV
          </NavLink>
        </div>
        
        <ul className={styles.navList}>
          {navItems.map(item => (
            <li key={item.path} className={styles.navItem}>
              <NavLink
                to={item.path}
                className={({ isActive }) => 
                  `${styles.navLink} ${isActive ? styles.active : ''}`
                }
              >
                <span className={styles.navIcon}>{item.icon}</span>
                <span className={styles.navLabel}>{item.label}</span>
                {item.badge && (
                  <span className={styles.badge}>{item.badge}</span>
                )}
              </NavLink>
            </li>
          ))}
        </ul>
        
        <div className={styles.progressIndicator}>
          <ProgressIndicator currentPath={location.pathname} />
        </div>
      </div>
    </nav>
  );
}

function ProgressIndicator({ currentPath }) {
  const steps = [
    { path: '/coupon', label: '쿠폰', order: 0 },
    { path: '/movies', label: '영화선택', order: 1 },
    { path: '/seats', label: '좌석선택', order: 2 },
    { path: '/payment', label: '결제', order: 3 },
    { path: '/success', label: '완료', order: 4 }
  ];
  
  const currentStep = steps.find(step => step.path === currentPath);
  const currentOrder = currentStep?.order ?? -1;
  
  // 예매 과정이 아닌 경우 표시하지 않음
  if (currentPath === '/' || currentPath === '/history' || currentOrder === -1) {
    return null;
  }
  
  return (
    <div className={styles.progressBar}>
      {steps.map((step, index) => (
        <div 
          key={step.path}
          className={`${styles.step} ${
            index <= currentOrder ? styles.completed : ''
          } ${index === currentOrder ? styles.current : ''}`}
        >
          <div className={styles.stepNumber}>{index + 1}</div>
          <div className={styles.stepLabel}>{step.label}</div>
        </div>
      ))}
    </div>
  );
}