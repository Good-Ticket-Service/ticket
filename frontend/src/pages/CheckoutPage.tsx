import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, errorMessage, rawApi } from '../api/client';
import type { Checkout, PageC, PgProvider, Reservation, UserCoupon } from '../api/types';
import { ErrorNotice, Loading, Title } from '../components/Common';
import { clock, formatDateTime, parseTime, won } from '../lib/format';
import { seatLabel } from '../lib/seatLabels';

const MIN_PAYMENT = 100;

function useSecondsLeft(until: string | undefined): number | null {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  return until ? Math.floor((parseTime(until).getTime() - now) / 1000) : null;
}

interface Authorized {
  orderId: string;
  pgAuthToken: string;
}

export function CheckoutPage() {
  const { reservationId } = useParams();
  const navigate = useNavigate();
  const client = useQueryClient();
  // undefined면 아직 고르지 않은 것이다. 이때는 예약에 잡힌 쿠폰을 유지한다.
  // checkout은 쿠폰을 보내지 않으면 잡은 쿠폰을 빼므로, 다시 결제할 때도 잡힌 쿠폰을 그대로 보내야 한다.
  const [pickedCoupon, setPickedCoupon] = useState<number | null | undefined>(undefined);
  const [provider] = useState<PgProvider>('TOSS');
  // 503이면 예약은 HOLD 그대로라 같은 인증값으로 다시 승인한다. 결제창을 다시 열지 않게 들고 있는다.
  const [authorized, setAuthorized] = useState<Authorized | null>(null);
  const path = `/api/reservations/${reservationId}`;

  const reservation = useQuery({ queryKey: ['reservation', reservationId], queryFn: () => api<Reservation>(path) });
  const coupons = useQuery({
    queryKey: ['coupons', 'me', 'ALL'],
    queryFn: () => api<PageC<UserCoupon>>('/api/coupons/me?size=50'),
  });
  const secondsLeft = useSecondsLeft(reservation.data?.holdExpiresAt);
  const couponId = pickedCoupon === undefined ? reservation.data?.userCouponId ?? null : pickedCoupon;

  const confirm = async (auth: Authorized) => {
    const result = await api<Reservation>(`${path}/payments/confirm`, { method: 'POST', body: auth });
    setAuthorized(null);
    return result;
  };

  const pay = useMutation({
    mutationFn: async () => {
      if (authorized) return confirm(authorized);
      const checkout = await api<Checkout>(`${path}/payments/checkout`, {
        method: 'POST',
        body: { pgProvider: provider, ...(couponId === null ? {} : { userCouponId: couponId }) },
      });
      // 운영 결제창 자리다. 지금은 Fake PG의 인증 시뮬레이션으로 인증값을 받는다.
      const { paymentKey } = await rawApi<{ paymentKey: string }>('/fake-pg/fake/toss/checkout', {
        orderId: checkout.orderId,
        amount: checkout.amount,
      });
      const auth = { orderId: checkout.orderId, pgAuthToken: paymentKey };
      setAuthorized(auth);
      return confirm(auth);
    },
    onSuccess: (result) => {
      client.setQueryData(['reservation', reservationId], result);
      client.invalidateQueries({ queryKey: ['coupons'] });
      navigate(`/reservations/${reservationId}`, { replace: true });
    },
    onError: (error) => {
      const code = error instanceof ApiError ? error.code : '';
      if (code === 'COUPON_NOT_AVAILABLE') setPickedCoupon(null);
      if (code !== 'SERVICE_UNAVAILABLE') setAuthorized(null);
      if (code === 'HOLD_EXPIRED' || code === 'ORDER_ID_MISMATCH' || code === 'CHECKOUT_CONFLICT') reservation.refetch();
    },
  });

  const release = useMutation({
    mutationFn: () =>
      api(`/api/seats/schedules/${reservation.data?.scheduleId}/holds/${reservationId}`, { method: 'DELETE' }),
    onSuccess: () => navigate(`/performances/${reservation.data?.performanceId}`, { replace: true }),
  });

  if (reservation.isPending) return <main className="content"><Loading /></main>;
  if (reservation.isError) return <main className="content"><ErrorNotice error={reservation.error} /></main>;
  const item = reservation.data;
  if (item.status !== 'HOLD') return <Navigate to={`/reservations/${reservationId}`} replace />;

  const expired = secondsLeft !== null && secondsLeft <= 0;
  const urgent = secondsLeft !== null && secondsLeft <= 120;
  // 이 예약에 이미 잡힌 쿠폰은 상태가 RESERVED라 함께 보여 준다.
  const available = (coupons.data?.content ?? []).filter(
    (c) => c.status === 'AVAILABLE' || c.userCouponId === item.userCouponId,
  );
  const coupon = available.find((c) => c.userCouponId === couponId);
  // 실제 결제 금액은 checkout 응답이 정한다. 여기 값은 같은 규칙으로 계산한 미리보기다.
  const discount = coupon ? Math.min(coupon.discountAmount, Math.max(0, item.totalPrice - MIN_PAYMENT)) : 0;
  const amount = item.totalPrice - discount;
  const payError = pay.isError ? pay.error : null;
  const busy = payError instanceof ApiError && payError.code === 'SERVICE_UNAVAILABLE';

  return (
    <>
      <div role="timer" aria-label="좌석 선점 남은 시간" className={urgent ? 'timerbar urgent' : 'timerbar'}>
        <span>{urgent ? '곧 좌석이 풀려요' : '좌석을 잡아 두는 시간'}</span>
        <span className="time num">{clock(secondsLeft ?? 0)}</span>
      </div>
      <main className="content medium">
        <Title en="Checkout" ko="결제" />
        <div className="split">
          <section className="main stack" style={{ gap: 28 }}>
            {busy && <p className="notice" role="alert">결제 요청이 몰리고 있어요. 좌석은 그대로 잡혀 있으니 잠시 뒤 다시 눌러 주세요.</p>}
            {payError && !busy && <p className="notice" role="alert">{errorMessage(payError)}</p>}
            {release.isError && <ErrorNotice error={release.error} />}
            <fieldset>
              <legend>쿠폰</legend>
              <label className="radio">
                <input
                  type="radio"
                  name="coupon"
                  checked={couponId === null}
                  onChange={() => setPickedCoupon(null)}
                />
                <span>사용 안 함</span>
              </label>
              {available.map((c) => (
                <label key={c.userCouponId} className="radio">
                  <input
                    type="radio"
                    name="coupon"
                    checked={couponId === c.userCouponId}
                    onChange={() => setPickedCoupon(c.userCouponId)}
                  />
                  <span>
                    {c.name} ({won(c.discountAmount)})<span className="sub num">{formatDateTime(c.expiresAt)}까지</span>
                  </span>
                </label>
              ))}
            </fieldset>
            <fieldset>
              <legend>결제 수단</legend>
              <div className="pg-grid">
                <button type="button" className="pg" aria-pressed="true">
                  토스페이먼츠
                </button>
                <button type="button" className="pg" disabled>
                  카카오페이 (준비 중)
                </button>
              </div>
            </fieldset>
          </section>
          <aside className="side panel stack">
            <div>
              <div style={{ fontSize: 24, fontWeight: 700, letterSpacing: '-0.02em' }}>{item.performanceTitle}</div>
              <div className="muted num" style={{ fontSize: 14 }}>
                {formatDateTime(item.scheduleStartAt)}
              </div>
              <div style={{ marginTop: 4, fontWeight: 500 }}>{seatLabel(item.reservationId) ?? `좌석 ${item.tickets.length}장`}</div>
            </div>
            <dl className="rows num">
              <div className="row">
                <dt>티켓 가격</dt>
                <dd>{won(item.totalPrice)}</dd>
              </div>
              <div className="row">
                <dt>쿠폰 할인</dt>
                <dd>{discount ? `-${won(discount)}` : '0원'}</dd>
              </div>
              <div className="row">
                <dt>결제 금액</dt>
                <dd className="total">{won(amount)}</dd>
              </div>
            </dl>
            <button type="button" className="btn btn-primary" disabled={pay.isPending || expired} onClick={() => pay.mutate()}>
              {expired ? '시간이 끝났어요' : pay.isPending ? '결제 중이에요' : `${won(amount)} 결제하기`}
            </button>
            <button type="button" className="btn-link" disabled={release.isPending || pay.isPending} onClick={() => release.mutate()}>
              좌석 놓고 나가기
            </button>
          </aside>
        </div>
      </main>
    </>
  );
}
