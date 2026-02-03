import React from 'react';
import { useContext, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useFormStatus, useActionState } from 'react';
import { AppContext } from '../context/AppContext';
import styles from './PaymentPage.module.css';

// React 19의 Action을 활용한 결제 처리
async function processPaymentAction(prevState, formData) {
  const paymentMethod = formData.get('paymentMethod');
  const useCoupon = formData.get('useCoupon') === 'true';
  
  // 결제 처리 시뮬레이션 (실제로는 PG사 API 호출)
  await new Promise(resolve => setTimeout(resolve, 2000));
  
  // 5% 확률로 결제 실패
  if (Math.random() < 0.05) {
    return { 
      success: false, 
      error: '결제 처리 중 오류가 발생했습니다. 다시 시도해주세요.' 
    };
  }
  
  return { 
    success: true, 
    paymentMethod,
    useCoupon,
    transactionId: 'TXN' + Date.now()
  };
}

export default function PaymentPage() {
  const { state, dispatch } = useContext(AppContext);
  const navigate = useNavigate();
  const [actionState, submitAction] = useActionState(processPaymentAction, null);
  const [paymentMethod, setPaymentMethod] = useState('card');
  const [useCoupon, setUseCoupon] = useState(false);
  
  const { selectedMovie, selectedTheater, selectedTime, selectedSeats, totalPrice } = state.booking;
  const { userCoupon } = state.coupon;
  
  if (!selectedMovie || selectedSeats.length === 0) {
    navigate('/movies');
    return null;
  }
  
  const discountAmount = useCoupon && userCoupon ? 2000 : 0;
  const finalPrice = totalPrice - discountAmount;
  
  const handlePayment = async (formData) => {
    const result = await submitAction(formData);
    
    if (result?.success) {
      // 결제 성공 시 예매 완료 처리
      dispatch({
        type: 'COMPLETE_BOOKING',
        payload: { usedCoupon: result.useCoupon ? userCoupon : null }
      });
      navigate('/success', { 
        state: { transactionId: result.transactionId } 
      });
    }
  };
  
  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <h1>결제</h1>
      </div>
      
      <div className={styles.content}>
        <div className={styles.orderSummary}>
          <h2>예매 정보</h2>
          <div className={styles.movieInfo}>
            <div className={styles.moviePoster}>
              <img src={selectedMovie.poster} alt={selectedMovie.title} />
            </div>
            <div className={styles.details}>
              <h3>{selectedMovie.title}</h3>
              <p>{selectedTheater.name}</p>
              <p>{selectedTime}</p>
              <p>좌석: {selectedSeats.join(', ')}</p>
            </div>
          </div>
          
          <div className={styles.priceBreakdown}>
            <div className={styles.priceItem}>
              <span>티켓 가격 ({selectedSeats.length}매)</span>
              <span>{totalPrice.toLocaleString()}원</span>
            </div>
            {useCoupon && userCoupon && (
              <div className={styles.priceItem}>
                <span>쿠폰 할인</span>
                <span className={styles.discount}>-{discountAmount.toLocaleString()}원</span>
              </div>
            )}
            <div className={styles.totalPrice}>
              <span>총 결제금액</span>
              <span>{finalPrice.toLocaleString()}원</span>
            </div>
          </div>
        </div>
        
        <div className={styles.paymentForm}>
          <h2>결제 방법</h2>
          <form action={handlePayment}>
            <input type="hidden" name="paymentMethod" value={paymentMethod} />
            <input type="hidden" name="useCoupon" value={useCoupon} />
            
            <div className={styles.paymentMethods}>
              <label className={styles.paymentMethod}>
                <input
                  type="radio"
                  name="paymentMethodRadio"
                  value="card"
                  checked={paymentMethod === 'card'}
                  onChange={(e) => setPaymentMethod(e.target.value)}
                />
                <span>💳 신용카드</span>
              </label>
              <label className={styles.paymentMethod}>
                <input
                  type="radio"
                  name="paymentMethodRadio"
                  value="kakaopay"
                  checked={paymentMethod === 'kakaopay'}
                  onChange={(e) => setPaymentMethod(e.target.value)}
                />
                <span>💰 카카오페이</span>
              </label>
              <label className={styles.paymentMethod}>
                <input
                  type="radio"
                  name="paymentMethodRadio"
                  value="naverpay"
                  checked={paymentMethod === 'naverpay'}
                  onChange={(e) => setPaymentMethod(e.target.value)}
                />
                <span>🟢 네이버페이</span>
              </label>
            </div>
            
            {userCoupon && (
              <div className={styles.couponSection}>
                <label className={styles.couponOption}>
                  <input
                    type="checkbox"
                    checked={useCoupon}
                    onChange={(e) => setUseCoupon(e.target.checked)}
                  />
                  <span>할인쿠폰 사용 (2,000원 할인)</span>
                </label>
              </div>
            )}
            
            <PaymentButton finalPrice={finalPrice} />
            
            {actionState?.error && (
              <div className={styles.error}>
                {actionState.error}
              </div>
            )}
          </form>
        </div>
      </div>
    </div>
  );
}

// React 19의 useFormStatus를 활용한 버튼 컴포넌트
function PaymentButton({ finalPrice }) {
  const { pending } = useFormStatus();
  
  return (
    <button 
      type="submit" 
      className={styles.payButton}
      disabled={pending}
    >
      {pending ? (
        <>
          <div className={styles.spinner} />
          결제 처리 중...
        </>
      ) : (
        `${finalPrice.toLocaleString()}원 결제하기`
      )}
    </button>
  );
}