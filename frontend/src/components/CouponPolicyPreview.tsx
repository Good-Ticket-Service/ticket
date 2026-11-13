import type { CouponForm } from '../lib/adminCoupons';
import { couponTitle, couponConditions } from '../lib/coupons';
export function CouponPolicyPreview({ value }: { value: CouponForm }) {
  const policy = {
    discountType: value.discountType,
    discountAmount:
      value.discountType === 'FIXED' && value.discountAmount ? Number(value.discountAmount) : null,
    discountRate: value.discountType === 'RATE' && value.discountRate ? Number(value.discountRate) : null,
    maxDiscountAmount:
      value.discountType === 'RATE' && value.maxDiscountAmount ? Number(value.maxDiscountAmount) : null,
    minBaseAmount: value.minBaseAmount ? Number(value.minBaseAmount) : null,
    genre: value.genre || null,
  };
  return (
    <aside className="admin-coupon-preview">
      <span className="admin-coupon-preview-label">쿠폰 미리보기</span>
      <div className={`admin-coupon-ticket ${value.category === 'WELCOME' ? 'welcome-ticket' : ''}`}>
        <span>{value.category === 'WELCOME' ? 'WELCOME' : 'GENERAL'}</span>
        <strong>{couponTitle(policy)}</strong>
        <h3>{value.name || '쿠폰 이름'}</h3>
        {couponConditions(policy).map((text) => (
          <p key={text}>{text}</p>
        ))}
        <small>발급 후 {value.category === 'WELCOME' ? '7' : value.validDays || '—'}일간 사용</small>
      </div>
      <p className="account-hint">입력 중인 정책의 미리보기입니다. 실제 저장 조건은 서버가 검증합니다.</p>
    </aside>
  );
}
