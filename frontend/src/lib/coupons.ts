import type { CouponPolicy, UserCoupon } from '../api/types';
import { parseTime, won } from './format';
import { genreLabel } from './genres';

export function couponTitle(policy: CouponPolicy): string {
  if (policy.discountType === 'RATE' && policy.discountRate != null) return `${policy.discountRate}% 할인`;
  if (policy.discountType === 'FIXED' && policy.discountAmount != null)
    return `${won(policy.discountAmount)} 할인`;
  return '할인 정보 확인 필요';
}

export function couponConditions(coupon: CouponPolicy & { genre: string | null }): string[] {
  return [
    coupon.genre ? `${genreLabel(coupon.genre)} 공연 전용` : '모든 장르',
    ...(coupon.minBaseAmount != null ? [`티켓 금액 ${won(coupon.minBaseAmount)} 이상`] : []),
    ...(coupon.maxDiscountAmount != null ? [`최대 ${won(coupon.maxDiscountAmount)} 할인`] : []),
  ];
}

/** 화면용 예상 금액이다. 실제 사용 가능 여부·할인액은 예약의 checkout 응답이 확정한다. */
export function couponPreview(
  coupon: UserCoupon,
  base: number,
  now: number,
  genre: string | null | undefined,
  reservedCouponId: number | null,
) {
  const reserved =
    coupon.userCouponId === reservedCouponId &&
    (coupon.status === 'RESERVED' || coupon.status === 'AVAILABLE');
  const blocked = (reason: string) => ({ discount: 0, reason });
  if (!Number.isSafeInteger(base) || base < 0) return blocked('티켓 금액 확인 필요');
  if (!reserved && coupon.status !== 'AVAILABLE')
    return blocked(coupon.status === 'RESERVED' ? '다른 예매에서 사용 중' : '사용할 수 없는 쿠폰');
  if (
    !reserved &&
    (!Number.isFinite(parseTime(coupon.expiresAt).getTime()) || parseTime(coupon.expiresAt).getTime() <= now)
  )
    return blocked('유효기간 만료');
  if (base <= 100) return blocked('티켓 금액이 100원을 초과해야 사용 가능');
  if (coupon.minBaseAmount != null && base < coupon.minBaseAmount)
    return blocked(`${won(coupon.minBaseAmount)} 이상 필요`);
  if (!reserved && coupon.genre && !genre) return blocked('공연 장르 확인 필요');
  if (!reserved && coupon.genre && coupon.genre !== genre)
    return blocked(`${genreLabel(coupon.genre)} 공연 전용`);
  let discount: number;
  if (
    coupon.discountType === 'RATE' &&
    coupon.discountRate != null &&
    coupon.discountRate >= 1 &&
    coupon.discountRate <= 99
  ) {
    discount = Math.min(Math.floor((base * coupon.discountRate) / 100), 2_147_483_647);
    if (coupon.maxDiscountAmount != null) discount = Math.min(discount, coupon.maxDiscountAmount);
  } else if (coupon.discountType === 'FIXED' && coupon.discountAmount != null && coupon.discountAmount > 0) {
    discount = coupon.discountAmount;
  } else return blocked('할인 정보 확인 필요');
  return { discount: Math.min(discount, base - 100), reason: null };
}
