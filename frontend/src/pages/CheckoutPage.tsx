import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, errorMessage, getToken, rawApi } from '../api/client';
import type { Checkout, PageC, PerformanceDetail, Reservation, UserCoupon } from '../api/types';
import { ErrorNotice, Loading, Poster } from '../components/Common';
import { useMe } from '../components/Layout';
import {
  CheckoutChangedError,
  runPaymentAttempt,
  type PaymentAuthorization,
  type PreparedCheckout,
} from '../lib/checkoutFlow';
import { couponConditions, couponPreview, couponTitle } from '../lib/coupons';
import { clock, formatDateTime, parseTime, won } from '../lib/format';
import { genreLabel } from '../lib/genres';
import { seatLabel } from '../lib/seatLabels';

export function CheckoutPage() {
  const { reservationId } = useParams();
  if (!reservationId || !/^[1-9]\d*$/.test(reservationId))
    return (
      <main className="content">
        <p className="notice">올바른 예매를 선택해 주세요.</p>
        <Link to="/reservations">내 예매</Link>
      </main>
    );
  return <CheckoutView key={reservationId} reservationId={reservationId} />;
}

function CheckoutView({ reservationId }: { reservationId: string }) {
  const navigate = useNavigate();
  const client = useQueryClient();
  const me = useMe();
  const actionLock = useRef(false);
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const [pickedCoupon, setPickedCoupon] = useState<number | null | undefined>(undefined);
  const [prepared, setPrepared] = useState<PreparedCheckout | null>(null);
  const [authorized, setAuthorized] = useState<PaymentAuthorization | null>(null);
  const [priceNotice, setPriceNotice] = useState(false);
  const path = `/api/reservations/${reservationId}`;
  const reservation = useQuery({
    queryKey: ['reservation', reservationId, getToken()],
    queryFn: () => api<Reservation>(path),
    refetchInterval: (query) => (query.state.data?.status === 'HOLD' ? 5000 : false),
  });
  const performance = useQuery({
    queryKey: ['performance', String(reservation.data?.performanceId)],
    enabled: reservation.data !== undefined,
    queryFn: () => api<PerformanceDetail>(`/api/performances/${reservation.data!.performanceId}`),
  });
  const coupons = useInfiniteQuery({
    queryKey: ['coupons', 'me', 'pages', 'ALL', getToken()],
    initialPageParam: 0,
    queryFn: ({ pageParam }) => api<PageC<UserCoupon>>(`/api/coupons/me?size=50&page=${pageParam}`),
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
  });
  const item = reservation.data;
  const couponId = pickedCoupon === undefined ? (item?.userCouponId ?? null) : pickedCoupon;
  const list = coupons.data?.pages.flatMap((page) => page.content) ?? [];
  const choices = list.filter(
    (coupon) =>
      coupon.status === 'AVAILABLE' ||
      coupon.status === 'RESERVED' ||
      coupon.userCouponId === item?.userCouponId,
  );
  const coupon = choices.find((value) => value.userCouponId === couponId);
  const preview =
    coupon && item
      ? couponPreview(coupon, item.totalPrice, now, performance.data?.genre, item.userCouponId)
      : null;
  const existing = couponId !== null && couponId === item?.userCouponId;
  const discount =
    couponId === null
      ? 0
      : existing && item?.discountAmount != null
        ? item.discountAmount
        : preview && !preview.reason
          ? preview.discount
          : null;
  const expectedAmount = item && discount !== null ? item.totalPrice - discount : null;
  const draft = prepared?.couponId === couponId ? prepared : null;
  const amount = draft?.checkout.amount ?? expectedAmount;
  const selectedUnavailable = couponId !== null && !existing && (!coupon || !!preview?.reason);
  const readReservation = async () => {
    const latest = await api<Reservation>(path);
    client.setQueryData(['reservation', reservationId, getToken()], latest);
    return latest;
  };
  const pay = useMutation({
    mutationFn: () =>
      runPaymentAttempt(
        { couponId, expectedAmount, prepared: draft, authorization: authorized },
        {
          reservation: readReservation,
          prepare: (id) =>
            api<Checkout>(`${path}/payments/checkout`, {
              method: 'POST',
              body: { pgProvider: 'TOSS', ...(id === null ? {} : { userCouponId: id }) },
            }),
          authorize: async (orderId, serverAmount) =>
            (
              await rawApi<{ paymentKey: string }>('/fake-pg/fake/toss/checkout', {
                orderId,
                amount: serverAmount,
              })
            ).paymentKey,
          confirm: (auth) => api<Reservation>(`${path}/payments/confirm`, { method: 'POST', body: auth }),
          onPrepared: (value) => {
            setPrepared(value);
            client.setQueryData<Reservation>(['reservation', reservationId, getToken()], (current) =>
              current
                ? {
                    ...current,
                    orderId: value.checkout.orderId,
                    userCouponId: value.couponId,
                    paymentAmount: value.checkout.amount,
                    discountAmount: current.totalPrice - value.checkout.amount,
                  }
                : current,
            );
            client.invalidateQueries({ queryKey: ['coupons', 'me'] });
          },
          onAuthorized: setAuthorized,
        },
      ),
    onSuccess: (result) => {
      if (result.kind === 'prepared') {
        setPriceNotice(true);
        return;
      }
      setAuthorized(null);
      setPrepared(null);
      client.setQueryData(['reservation', reservationId, getToken()], result.value);
      client.invalidateQueries({ queryKey: ['coupons'] });
      client.invalidateQueries({ queryKey: ['reservations'] });
      navigate(`/reservations/${reservationId}`, { replace: true });
    },
    onError: (error) => {
      const code = error instanceof ApiError ? error.code : '';
      const recoverable = error instanceof ApiError && (error.status === 0 || error.status >= 500);
      if (!recoverable) {
        setAuthorized(null);
        setPrepared(null);
      }
      if (code === 'COUPON_NOT_AVAILABLE') {
        setPickedCoupon(null);
        client.invalidateQueries({ queryKey: ['coupons'] });
      }
      if (error instanceof CheckoutChangedError) setPickedCoupon(undefined);
      reservation.refetch();
    },
    onSettled: () => {
      actionLock.current = false;
    },
  });
  const release = useMutation({
    mutationFn: () =>
      api(`/api/seats/schedules/${item!.scheduleId}/holds/${reservationId}`, { method: 'DELETE' }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['coupons'] });
      client.invalidateQueries({ queryKey: ['reservations'] });
      navigate(`/performances/${item!.performanceId}`, { replace: true });
    },
    onError: () => reservation.refetch(),
    onSettled: () => {
      actionLock.current = false;
    },
  });
  if (reservation.isPending)
    return (
      <main className="content">
        <Loading />
      </main>
    );
  if (reservation.isError || !item)
    return (
      <main className="content">
        <ErrorNotice error={reservation.error} />
        <button type="button" className="btn btn-ghost" onClick={() => reservation.refetch()}>
          예매 다시 확인
        </button>
      </main>
    );
  if (item.status !== 'HOLD') return <Navigate to={`/reservations/${reservationId}`} replace />;
  const secondsLeft = Math.floor((parseTime(item.holdExpiresAt).getTime() - now) / 1000);
  const expired = secondsLeft <= 0;
  const busy = pay.isPending || release.isPending;
  const freeze = busy || authorized !== null;
  const actualDiscount = amount === null ? null : item.totalPrice - amount;
  const changeCoupon = (value: string) => {
    if (freeze) return;
    setPickedCoupon(value === '' ? null : Number(value));
    setPrepared(null);
    setPriceNotice(false);
    pay.reset();
  };
  const buttonText = pay.isPending
    ? '결제 요청을 처리하고 있어요'
    : authorized
      ? '같은 요청으로 결제 상태 확인'
      : expired
        ? '선점 시간이 끝났어요'
        : amount === null
          ? '결제 금액 확인'
          : `${won(amount)} 결제하기`;
  return (
    <main className="content checkout-content">
      <ol className="booking-steps" aria-label="예매 진행 단계">
        <li>
          <span>1</span>좌석 선택
        </li>
        <li aria-current="step">
          <span>2</span>주문·결제
        </li>
        <li>
          <span>3</span>예매 완료
        </li>
      </ol>
      <h1 className="section-marker checkout-heading">주문·결제</h1>
      {pay.isError && (
        <div className="checkout-alert" role="alert">
          {authorized
            ? '결제 결과를 아직 확인하지 못했어요. 같은 요청으로 상태를 확인해 주세요.'
            : pay.error instanceof CheckoutChangedError
              ? pay.error.message
              : errorMessage(pay.error)}
        </div>
      )}
      {release.isError && <ErrorNotice error={release.error} />}
      <div className="checkout-layout">
        <div className="checkout-main">
          <section className="checkout-panel">
            <h2>예매 상품</h2>
            <div className="checkout-product">
              <div className="checkout-poster">
                <Poster src={performance.data?.posterUrl ?? null} />
              </div>
              <div>
                {performance.data && (
                  <span className="detail-genre">{genreLabel(performance.data.genre)}</span>
                )}
                <h3>{item.performanceTitle}</h3>
                <p>{formatDateTime(item.scheduleStartAt)}</p>
                {performance.data && <p>{performance.data.venue.name}</p>}
                <p>{seatLabel(item.reservationId) ?? `좌석 ${item.tickets.length}장`}</p>
                <p className="checkout-reference">예매 번호 {item.reservationId}</p>
              </div>
            </div>
            {performance.isError && (
              <div className="checkout-inline-error">
                <p>공연 부가 정보를 불러오지 못했어요. 예매 상품·일정은 선점 내역을 기준으로 표시해요.</p>
                <button type="button" className="booking-refresh" onClick={() => performance.refetch()}>
                  공연 정보 다시 확인
                </button>
              </div>
            )}
          </section>
          <section className="checkout-panel">
            <h2>예매자 정보</h2>
            {me.isPending ? (
              <Loading />
            ) : me.isError ? (
              <ErrorNotice error={me.error} />
            ) : (
              <dl className="checkout-booker">
                <div>
                  <dt>이름</dt>
                  <dd>{me.data?.name}</dd>
                </div>
                <div>
                  <dt>이메일</dt>
                  <dd>{me.data?.email}</dd>
                </div>
                <div>
                  <dt>연락처</dt>
                  <dd>{me.data?.phone}</dd>
                </div>
              </dl>
            )}
            <p className="checkout-caption">로그인한 회원 정보로 예매해요.</p>
          </section>
          <section className="checkout-panel checkout-coupons">
            <h2>
              <span className="coupon-mark" aria-hidden="true">
                %
              </span>{' '}
              할인·쿠폰
            </h2>
            <label className="checkout-coupon-label" htmlFor="checkout-coupon">
              쿠폰 선택
            </label>
            <select
              id="checkout-coupon"
              className="input"
              value={couponId ?? ''}
              disabled={freeze}
              onChange={(event) => changeCoupon(event.target.value)}
            >
              <option value="">쿠폰 사용 안 함</option>
              {existing && !coupon && (
                <option value={couponId!}>
                  기존 적용 쿠폰{item.discountAmount != null ? ` · ${won(item.discountAmount)} 할인` : ''}
                </option>
              )}
              {choices.map((value) => {
                const check = couponPreview(
                  value,
                  item.totalPrice,
                  now,
                  performance.data?.genre,
                  item.userCouponId,
                );
                const current = value.userCouponId === item.userCouponId;
                return (
                  <option
                    key={value.userCouponId}
                    value={value.userCouponId}
                    disabled={!current && !!check.reason}
                  >
                    {value.name ?? '쿠폰'} · {couponTitle(value)}
                    {current ? ' · 현재 적용' : check.reason ? ` · ${check.reason}` : ''}
                  </option>
                );
              })}
            </select>
            {coupon && (
              <div className="checkout-coupon-terms">
                <strong>{coupon.name ?? '선택 쿠폰'}</strong>
                <p>{couponConditions(coupon).join(' · ')}</p>
                <p>
                  {existing
                    ? '현재 예매에 적용된 쿠폰이에요.'
                    : `${formatDateTime(coupon.expiresAt)}까지 사용 가능`}
                </p>
              </div>
            )}
            {coupons.isPending && <Loading />}
            {coupons.isError && (
              <div className="checkout-inline-error">
                <ErrorNotice error={coupons.error} />
                <button type="button" className="booking-refresh" onClick={() => coupons.refetch()}>
                  보유 쿠폰 다시 불러오기
                </button>
              </div>
            )}
            {coupons.hasNextPage && (
              <button
                type="button"
                className="booking-refresh"
                disabled={coupons.isFetchingNextPage || freeze}
                onClick={() => coupons.fetchNextPage()}
              >
                보유 쿠폰 더 불러오기
              </button>
            )}
            {authorized && (
              <p className="checkout-caption">진행 중인 결제의 쿠폰은 결과를 확인한 뒤 변경할 수 있어요.</p>
            )}
            <p className="checkout-caption">
              쿠폰은 한 장만 사용해요. 조건과 할인액은 서버에서 최종 확인하며, 할인 후 결제액은 최소
              100원이에요.
            </p>
            {!freeze && (
              <Link className="checkout-wallet-link" to="/coupons">
                쿠폰함 보기 ↗
              </Link>
            )}
          </section>
          <section className="checkout-panel">
            <h2>결제 수단</h2>
            <div className="pg-grid">
              <button type="button" className="pg" aria-pressed="true">
                토스페이먼츠
              </button>
              <button type="button" className="pg" disabled>
                카카오페이 · 준비 중
              </button>
            </div>
            <p className="checkout-caption">현재는 테스트 결제 환경이에요. 실제 요금이 청구되지 않아요.</p>
          </section>
        </div>
        <aside className="checkout-summary" aria-labelledby="payment-heading">
          <div className="checkout-summary-heading">
            <h2 id="payment-heading">결제 정보</h2>
            <div
              role="timer"
              aria-label="좌석 선점 남은 시간"
              className={secondsLeft <= 120 ? 'checkout-countdown urgent' : 'checkout-countdown'}
            >
              {clock(Math.max(0, secondsLeft))}
            </div>
          </div>
          <p className="checkout-caption">
            {expired
              ? '선점 시간이 끝났어요. 최신 예매 상태를 확인해 주세요.'
              : '남은 시간 안에 결제를 완료해 주세요.'}
          </p>
          <dl className="checkout-totals">
            <div>
              <dt>티켓 금액 ({item.tickets.length}매)</dt>
              <dd>{won(item.totalPrice)}</dd>
            </div>
            <div>
              <dt>쿠폰 할인</dt>
              <dd>
                {actualDiscount === null
                  ? '서버 확인 필요'
                  : actualDiscount > 0
                    ? `−${won(actualDiscount)}`
                    : '0원'}
              </dd>
            </div>
            <div className="checkout-grand-total">
              <dt>{draft ? '확인된 결제 금액' : '예상 결제 금액'}</dt>
              <dd>{amount === null ? '확인 필요' : won(amount)}</dd>
            </div>
          </dl>
          {priceNotice && (
            <p className="checkout-price-notice" role="status">
              서버에서 확인한 금액으로 갱신했어요. 금액을 확인하고 결제해 주세요.
            </p>
          )}
          <button
            type="button"
            className="btn btn-primary btn-block"
            disabled={busy || (!authorized && (expired || selectedUnavailable))}
            onClick={() => {
              if (actionLock.current) return;
              actionLock.current = true;
              pay.mutate();
            }}
          >
            {buttonText}
          </button>
          <button
            type="button"
            className="checkout-release"
            disabled={busy || authorized !== null}
            onClick={() => {
              if (actionLock.current) return;
              actionLock.current = true;
              release.mutate();
            }}
          >
            좌석 놓고 나가기
          </button>
          {expired && (
            <Link className="btn btn-ghost btn-block" to={`/reservations/${reservationId}`}>
              예매 상태 확인
            </Link>
          )}
          <div className="checkout-payment-note">
            <h3>예매·결제 안내</h3>
            <p>결제 결과를 확인 중일 때에는 새 결제를 만들지 않고 같은 요청으로 확인해요.</p>
            <p>좌석을 놓고 나가면 현재 선점이 해제돼요.</p>
          </div>
        </aside>
      </div>
    </main>
  );
}
