import type { Checkout, Reservation } from '../api/types';

export interface PreparedCheckout {
  checkout: Checkout;
  couponId: number | null;
}
export interface PaymentAuthorization {
  orderId: string;
  pgAuthToken: string;
}
export class CheckoutChangedError extends Error {
  constructor() {
    super('예매의 결제 요청이 변경됐어요. 최신 상태를 확인한 뒤 다시 진행해 주세요.');
  }
}

interface Attempt {
  couponId: number | null;
  expectedAmount: number | null;
  prepared: PreparedCheckout | null;
  authorization: PaymentAuthorization | null;
}
interface PaymentApi {
  reservation: () => Promise<Reservation>;
  prepare: (couponId: number | null) => Promise<Checkout>;
  authorize: (orderId: string, amount: number) => Promise<string>;
  confirm: (auth: PaymentAuthorization) => Promise<Reservation>;
  onPrepared: (prepared: PreparedCheckout) => void;
  onAuthorized: (auth: PaymentAuthorization) => void;
}

/** 승인 재시도는 기존 orderId·인증값을 유지하고 새 결제 준비를 만들지 않는다. */
export async function runPaymentAttempt(
  attempt: Attempt,
  api: PaymentApi,
): Promise<{ kind: 'prepared' } | { kind: 'reservation'; value: Reservation }> {
  if (attempt.authorization) {
    const current = await api.reservation();
    if (current.status !== 'HOLD') return { kind: 'reservation', value: current };
    if (current.orderId !== attempt.authorization.orderId) throw new CheckoutChangedError();
    return { kind: 'reservation', value: await api.confirm(attempt.authorization) };
  }
  let prepared = attempt.prepared?.couponId === attempt.couponId ? attempt.prepared : null;
  if (prepared) {
    const current = await api.reservation();
    if (current.status !== 'HOLD') return { kind: 'reservation', value: current };
    if (current.orderId !== prepared.checkout.orderId) throw new CheckoutChangedError();
  } else {
    prepared = { couponId: attempt.couponId, checkout: await api.prepare(attempt.couponId) };
    api.onPrepared(prepared);
    // 예상과 서버 금액이 다르면 갱신된 금액을 먼저 보여 주고 다음 클릭에서 진행한다.
    if (attempt.expectedAmount !== prepared.checkout.amount) return { kind: 'prepared' };
  }
  const auth = {
    orderId: prepared.checkout.orderId,
    pgAuthToken: await api.authorize(prepared.checkout.orderId, prepared.checkout.amount),
  };
  api.onAuthorized(auth);
  return { kind: 'reservation', value: await api.confirm(auth) };
}
