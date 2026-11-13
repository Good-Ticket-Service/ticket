import type { Coupon, CouponInput, CouponIssueStatus } from '../api/manageTypes';
import { parseTime, seoulInputToLocal, toSeoulInput } from './format';
export const ISSUE_STATUS_LABEL: Record<CouponIssueStatus, string> = {
  UPCOMING: '발급 예정',
  ISSUING: '발급 중',
  ENDED: '발급 종료',
};
export interface CouponForm {
  name: string;
  category: 'GENERAL' | 'WELCOME';
  genre: string;
  discountType: 'FIXED' | 'RATE';
  discountAmount: string;
  discountRate: string;
  maxDiscountAmount: string;
  minBaseAmount: string;
  totalQuantity: string;
  validDays: string;
  issueStartAt: string;
  issueEndAt: string;
}
export function newCouponForm(): CouponForm {
  return {
    name: '',
    category: 'GENERAL',
    genre: '',
    discountType: 'FIXED',
    discountAmount: '3000',
    discountRate: '10',
    maxDiscountAmount: '',
    minBaseAmount: '',
    totalQuantity: '100',
    validDays: '30',
    issueStartAt: toSeoulInput(undefined, 10),
    issueEndAt: toSeoulInput(undefined, 7 * 24 * 60),
  };
}
export function toCouponForm(c: Coupon): CouponForm {
  return {
    name: c.name,
    category: c.category,
    genre: c.genre ?? '',
    discountType: c.discountType,
    discountAmount: c.discountAmount?.toString() ?? '',
    discountRate: c.discountRate?.toString() ?? '',
    maxDiscountAmount: c.maxDiscountAmount?.toString() ?? '',
    minBaseAmount: c.minBaseAmount?.toString() ?? '',
    totalQuantity: String(c.totalQuantity),
    validDays: String(c.validDays),
    issueStartAt: toSeoulInput(c.issueStartAt),
    issueEndAt: toSeoulInput(c.issueEndAt),
  };
}
export function couponStatus(c: Coupon, now: number): CouponIssueStatus {
  if (c.issueStatus === 'ENDED' || parseTime(c.issueEndAt).getTime() <= now) return 'ENDED';
  return c.issueStatus === 'ISSUING' || parseTime(c.issueStartAt).getTime() <= now ? 'ISSUING' : 'UPCOMING';
}
export const issuedCount = (c: Coupon) => Math.max(0, c.totalQuantity - c.remainingQuantity);
function number(value: string, label: string, max = 2147483647) {
  const n = Number(value);
  if (!/^\d+$/.test(value) || !Number.isSafeInteger(n) || n < 1 || n > max)
    throw new Error(`${label}을 1~${max.toLocaleString('ko-KR')} 사이 정수로 입력해 주세요.`);
  return n;
}
function policy(f: CouponForm) {
  return {
    discountType: f.discountType,
    ...(f.discountType === 'FIXED'
      ? { discountAmount: number(f.discountAmount, '할인 금액') }
      : {
          discountRate: number(f.discountRate, '할인율', 99),
          ...(f.maxDiscountAmount ? { maxDiscountAmount: number(f.maxDiscountAmount, '최대 할인액') } : {}),
        }),
    ...(f.minBaseAmount ? { minBaseAmount: number(f.minBaseAmount, '최소 티켓 금액') } : {}),
  };
}
function name(value: string) {
  if (!value.trim() || value.length > 100)
    throw new Error('쿠폰 이름은 공백을 제외한 1~100자로 입력해 주세요.');
  return value;
}
function period(f: CouponForm, now: number) {
  const start = parseTime(seoulInputToLocal(f.issueStartAt)).getTime(),
    end = parseTime(seoulInputToLocal(f.issueEndAt)).getTime();
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= Math.max(start, now))
    throw new Error('발급 종료는 시작 시각과 현재 시각보다 뒤여야 해요.');
}
export function createCouponBody(f: CouponForm, now = Date.now()): CouponInput {
  period(f, now);
  const days = number(f.validDays, '유효 기간');
  if (f.category === 'WELCOME' && days !== 7) throw new Error('웰컴 쿠폰의 유효 기간은 7일입니다.');
  return {
    name: name(f.name),
    category: f.category,
    ...(f.genre ? { genre: f.genre } : {}),
    ...policy(f),
    totalQuantity: number(f.totalQuantity, '총 수량'),
    validDays: days,
    issueStartAt: seoulInputToLocal(f.issueStartAt),
    issueEndAt: seoulInputToLocal(f.issueEndAt),
  };
}
/** 정책은 일부 값만 수정해도 현재 입력의 전체 묶음을 보낸다. 나머지 필드는 바뀐 것만 보낸다. */
export function updateCouponBody(
  f: CouponForm,
  before: Coupon,
  current = before,
  now = Date.now(),
): Partial<CouponInput> {
  const old = toCouponForm(before),
    body: Partial<CouponInput> = {};
  if (f.name !== old.name) body.name = name(f.name);
  if (f.category !== old.category) body.category = f.category;
  if (f.genre !== old.genre) {
    if (!f.genre) throw new Error('장르 쿠폰을 전체 장르로 바꾸려면 삭제 후 새로 만들어야 합니다.');
    body.genre = f.genre;
  }
  const nextPolicy = policy(f),
    oldPolicy = policy(old);
  if (JSON.stringify(nextPolicy) !== JSON.stringify(oldPolicy)) Object.assign(body, nextPolicy);
  if (f.totalQuantity !== old.totalQuantity) {
    body.totalQuantity = number(f.totalQuantity, '총 수량');
    if (body.totalQuantity < issuedCount(current))
      throw new Error('총 수량은 이미 발급된 수량 이상이어야 해요.');
  }
  if (f.validDays !== old.validDays) body.validDays = number(f.validDays, '유효 기간');
  if (body.category === 'WELCOME') body.validDays = 7;
  if (f.issueStartAt !== old.issueStartAt) body.issueStartAt = seoulInputToLocal(f.issueStartAt);
  if (f.issueEndAt !== old.issueEndAt) body.issueEndAt = seoulInputToLocal(f.issueEndAt);
  const status = couponStatus(current, now);
  if (
    status !== 'UPCOMING' &&
    Object.keys(body).some((key) => !['name', 'totalQuantity', 'issueEndAt'].includes(key))
  )
    throw new Error('발급 시작 후에는 이름·총 수량·발급 종료만 수정할 수 있어요.');
  if (status === 'ENDED' && Object.keys(body).some((key) => key !== 'name'))
    throw new Error('발급이 끝난 쿠폰은 이름만 수정할 수 있어요.');
  if ((body.category ?? current.category) === 'WELCOME' && (body.validDays ?? current.validDays) !== 7)
    throw new Error('웰컴 쿠폰의 유효 기간은 7일입니다.');
  if (body.issueStartAt || body.issueEndAt) period(f, now);
  return body;
}
