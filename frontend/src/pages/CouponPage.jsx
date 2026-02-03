// src/pages/CouponPage.jsx
import React from 'react';
import { useContext, useOptimistic, useActionState } from 'react';
import { AppContext } from '../context/AppContext';
import styles from './CouponPage.module.css';
import { fetchWithSession } from '../api/session';
// 개발용: 필요 시 Vite 프록시를 쓰면 '' 로 비워도 됩니다.

const API_BASE = import.meta.env.VITE_API_BASE;
function genRequestId() {
  if (crypto?.randomUUID) return crypto.randomUUID();
  const s4 = () => Math.floor((1 + Math.random()) * 0x10000).toString(16).slice(-4);
  return `${s4()}${s4()}-${s4()}-${s4()}-${s4()}-${s4()}${s4()}${s4()}`;
}
/**
 * 실무 권장: 서버가 JSON(202)으로 requestId/waitUrl을 내려주면
 * 프론트가 top-level 네비게이션으로 /wait.html로 이동한다.
 * (리다이렉트+CORS 문제를 피하고, 에러 처리/로그도 쉬움)
 */
async function issueCouponAction(prevState, formData) {
  const requestId = genRequestId();
  console.group(`[DEBUG] Coupon Request @${new Date().toLocaleTimeString()}`);
  console.log('[DEBUG] requestId:', requestId);

  try {
    const res = await fetchWithSession(`${API_BASE}/api/coupons/request`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
      body: JSON.stringify({ requestId }),
      mode: 'cors',
    });

    console.log('[DEBUG] 2. 응답 수신', { status: res.status, ok: res.ok });
    
    if (res.status === 202) {
      const data = await res.json().catch(() => ({}));

      // ★★★★★ 수정된 부분 시작 ★★★★★
      // 1. 백엔드가 보낸 키 이름('myseq', 'waitUrl')으로 데이터를 받습니다.
      const { myseq, myRank, headSeq, waitUrl: basePath } = data;
      //    'waitUrl: basePath' 문법은 waitUrl 값을 basePath라는 새 변수 이름으로 할당합니다.
      const serverRequestId = data.requestId || requestId;

      // 2. 받은 변수명(myseq)으로 필수 데이터를 확인합니다.
      if (!serverRequestId || myseq == null) {
          console.error('[DEBUG] 대기열 필수 정보 누락:', data);
          return { success: false, error: '대기열 정보가 올바르지 않습니다.' };
      }

      // 3. URLSearchParams에 올바른 변수명(myseq)으로 값을 전달합니다.
      const params = new URLSearchParams({
          requestId: serverRequestId,
          mySeq: myseq, // ★ myseq 값을 mySeq 키로 전달
          myRank: myRank,
          headSeq: headSeq,
          type: 'coupon',
          id:'global'
      });
      // ★★★★★ 수정된 부분 끝 ★★★★★

      const finalWaitUrl = `${basePath.split('?')[0]}?${params.toString()}`;

      const absolute = finalWaitUrl.startsWith('http')
        ? finalWaitUrl
        : `${API_BASE}${finalWaitUrl.startsWith('/') ? '' : '/'}${finalWaitUrl}`;
        
      console.log('[DEBUG] 3. 대기 페이지로 이동:', absolute);
      window.location.href = absolute;
      return { success: true, queued: true };
    }

    // 200/201 = 즉시 발급 완료 → 성공 처리 (필요 시 응답 바디 사용)
    if (res.status === 200 || res.status === 201) {
      const data = await res.json().catch(() => null);
      return { success: true, issued: data };
    }

    // 그 외 2xx (ok=true)도 에러 아님 → 성공 처리
    if (res.ok) return { success: true };

    // 여기까지 왔다면 비-2xx → 에러
    const txt = await res.text().catch(() => '');
    return { success: false, error: `요청 실패(${res.status}). ${txt}`.trim() };
  } catch (e) {
    return { success: false, error: '네트워크 오류가 발생했습니다.' };
  }
}

export default function CouponPage() {
  const { state, dispatch } = useContext(AppContext);
  const [actionState, submitAction, isPending] = useActionState(issueCouponAction, null);

  // 낙관적 업데이트 (UI 즉시 반응)
  const [optimisticCoupon, addOptimisticCoupon] = useOptimistic(
    state.coupon,
    (currentCoupon, patch) => ({ ...currentCoupon, ...patch })
  );

  const handleIssueCoupon = async (formData) => {
    // 1) UI 먼저 업데이트
    const prevTotal = optimisticCoupon?.totalIssued ?? state.coupon?.totalIssued ?? 0;
    addOptimisticCoupon({
      totalIssued: prevTotal + 1,
      userCoupon: { isLoading: true, discount: 2000 },
    });

    // 2) 서버 액션 실행 → 성공 시 페이지 이동 (위 Action에서 수행)
    const result = await submitAction(formData);

    // 3) 혹시 JSON만 받고 이동 안 하는 플로우일 때의 보강 (거의 실행되지 않음)
    if (result?.success && !result.redirected) {
      dispatch({
        type: 'ISSUE_COUPON',
        payload: { discount: 2000, code: 'CGV' + Date.now().toString().slice(-6) },
      });
    }
  };

  const totalIssued = optimisticCoupon?.totalIssued ?? state.coupon?.totalIssued ?? 0;
  const maxCoupons = optimisticCoupon?.maxCoupons ?? state.coupon?.maxCoupons ?? 1;
  const remainingCoupons = Math.max(0, maxCoupons - totalIssued);
  const progressPercentage = Math.min(100, (totalIssued / maxCoupons) * 100);

  // AppContext에 user가 없을 수도 있으니 안전하게 기본값 처리
  const defaultUserId = state?.user?.id ?? 'u1';

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <h1>🎬 CGV 할인쿠폰</h1>
        <p>선착순 450만장 한정! 영화 관람 시 2,000원 할인</p>
      </div>

      <div className={styles.progress}>
        <div className={styles.progressInfo}>
          <span>발급된 쿠폰: {totalIssued.toLocaleString()}장</span>
          <span>남은 쿠폰: {remainingCoupons.toLocaleString()}장</span>
        </div>
        <div className={styles.progressBar}>
          <div className={styles.progressFill} style={{ width: `${progressPercentage}%` }} />
        </div>
        <div className={styles.progressPercentage}>{progressPercentage.toFixed(2)}% 발급 완료</div>
      </div>

      {optimisticCoupon?.userCoupon ? (
        <div className={styles.couponResult}>
          {optimisticCoupon.userCoupon.isLoading ? (
            <div className={styles.loading}>
              <div className={styles.spinner} />
              <p>쿠폰 발급 중...</p>
            </div>
          ) : (
            <div className={styles.success}>
              <h2>🎉 쿠폰 발급 완료!</h2>
              <div className={styles.coupon}>
                <div className={styles.couponHeader}>CGV 할인쿠폰</div>
                <div className={styles.couponAmount}>2,000원 할인</div>
                <div className={styles.couponCode}>쿠폰번호: {optimisticCoupon.userCoupon.id}</div>
              </div>
              <p>영화 예매 시 자동으로 적용됩니다!</p>
            </div>
          )}
        </div>
      ) : (
        // React 19 Actions: form action에 핸들러를 넣으면 formData가 자동 전달됨
        <form action={handleIssueCoupon} className={styles.couponForm}>
          <input type="hidden" name="userId" value={defaultUserId} />
          <button
            type="submit"
            disabled={isPending || remainingCoupons <= 0}
            className={styles.issueButton}
          >
            {isPending ? '발급 중...' : remainingCoupons <= 0 ? '쿠폰 소진' : '쿠폰 받기'}
          </button>

          {actionState?.error && <div className={styles.error}>{actionState.error}</div>}
        </form>
      )}

      <div className={styles.info}>
        <h3>쿠폰 사용 안내</h3>
        <ul>
          <li>1인 1매 발급 제한</li>
          <li>모든 영화 관람 시 사용 가능</li>
          <li>다른 할인과 중복 적용 불가</li>
          <li>발급일로부터 30일 유효</li>
        </ul>
      </div>
    </div>
  );
}
