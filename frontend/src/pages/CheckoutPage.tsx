import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, errorMessage, getToken, rawApi } from '../api/client';
import type { Checkout, Hold, HoldView, PageC, PerformanceDetail, Reservation, UserCoupon } from '../api/types';
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
import { HOLD_ID, RESELECT_CODES, reservationResolver } from '../lib/holdCheckout';
import { forgetHold } from '../lib/holdMemory';
import { saveSeatLabel, seatLabel } from '../lib/seatLabels';

/** 결제 화면을 여는 근거. DB 모드는 예약, REDIS 모드는 아직 예약이 없는 선점(holdId)이다. */
type CheckoutSource =
  | { kind: 'reservation'; reservationId: string }
  | { kind: 'hold'; scheduleId: string; holdId: string };

/** 결제 화면에 보이는 값. 예약과 선점 조회를 같은 모양으로 맞춘다. */
interface CheckoutItem {
  reservationId: number | null;
  scheduleId: number;
  performanceId: number;
  performanceTitle: string;
  scheduleStartAt: string;
  holdExpiresAt: string;
  totalPrice: number;
  userCouponId: number | null;
  discountAmount: number | null;
  ticketCount: number;
  seat: string | null;
}

/** REDIS 모드 선점의 결제가 좌석 선택 완료 뒤 이어지지 못했을 때 예약 결제 화면에 넘기는 안내. */
interface CheckoutNotice {
  message?: string;
  priceChanged?: boolean;
}

export function CheckoutPage() {
  const { reservationId } = useParams();
  if (!reservationId || !/^[1-9]\d*$/.test(reservationId))
    return (
      <main className="content">
        <p className="notice">올바른 예매를 선택해 주세요.</p>
        <Link to="/reservations">내 예매</Link>
      </main>
    );
  return <CheckoutView key={reservationId} source={{ kind: 'reservation', reservationId }} />;
}

export function HoldCheckoutPage() {
  const { scheduleId, holdId } = useParams();
  if (!scheduleId || !/^[1-9]\d*$/.test(scheduleId) || !holdId || !HOLD_ID.test(holdId))
    return (
      <main className="content">
        <p className="notice">올바른 선점을 선택해 주세요.</p>
        <Link to="/reservations">내 예매</Link>
      </main>
    );
  return <CheckoutView key={holdId} source={{ kind: 'hold', scheduleId, holdId }} />;
}

function CheckoutView({ source }: { source: CheckoutSource }) {
  const navigate = useNavigate();
  const location = useLocation();
  const client = useQueryClient();
  const me = useMe();
  const actionLock = useRef(false);
  const holdMode = source.kind === 'hold';
  const notice = (location.state as CheckoutNotice | null) ?? null;
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const [pickedCoupon, setPickedCoupon] = useState<number | null | undefined>(undefined);
  const [prepared, setPrepared] = useState<PreparedCheckout | null>(null);
  const [authorized, setAuthorized] = useState<PaymentAuthorization | null>(null);
  const [priceNotice, setPriceNotice] = useState(notice?.priceChanged === true);
  const reservationKey = (id: number | string) => ['reservation', String(id), getToken()];
  const reservation = useQuery({
    queryKey: reservationKey(source.kind === 'reservation' ? source.reservationId : ''),
    enabled: source.kind === 'reservation',
    queryFn: () => api<Reservation>(`/api/reservations/${(source as { reservationId: string }).reservationId}`),
    refetchInterval: (query) => (query.state.data?.status === 'HOLD' ? 5000 : false),
  });
  const holdPath =
    source.kind === 'hold' ? `/api/seats/schedules/${source.scheduleId}/holds/${source.holdId}` : '';
  const hold = useQuery({
    queryKey: ['hold', source.kind === 'hold' ? source.holdId : '', getToken()],
    enabled: holdMode,
    queryFn: () => api<HoldView>(holdPath),
    retry: false,
    // 다른 창에서 좌석 선택 완료를 처리하는 중이면 끝날 때까지 다시 본다
    refetchInterval: (query) => (query.state.data?.status === 'CONVERTING' ? 2000 : false),
  });
  const item: CheckoutItem | undefined = useMemo(() => {
    if (source.kind === 'hold') {
      const view = hold.data;
      if (!view) return undefined;
      return {
        reservationId: view.reservationId,
        scheduleId: view.scheduleId,
        performanceId: view.performanceId,
        performanceTitle: view.performanceTitle,
        scheduleStartAt: view.scheduleStartAt,
        holdExpiresAt: view.holdExpiresAt,
        totalPrice: view.basePrice,
        userCouponId: null,
        discountAmount: null,
        ticketCount: 1,
        seat: seatLabel(view.holdId) ?? `${view.gradeName} 좌석 1장`,
      };
    }
    const value = reservation.data;
    if (!value) return undefined;
    return {
      reservationId: value.reservationId,
      scheduleId: value.scheduleId,
      performanceId: value.performanceId,
      performanceTitle: value.performanceTitle,
      scheduleStartAt: value.scheduleStartAt,
      holdExpiresAt: value.holdExpiresAt,
      totalPrice: value.totalPrice,
      userCouponId: value.userCouponId,
      discountAmount: value.discountAmount,
      ticketCount: value.tickets.length,
      seat: seatLabel(value.reservationId) ?? `좌석 ${value.tickets.length}장`,
    };
  }, [source, hold.data, reservation.data]);
  const performance = useQuery({
    queryKey: ['performance', String(item?.performanceId)],
    enabled: item !== undefined,
    queryFn: () => api<PerformanceDetail>(`/api/performances/${item!.performanceId}`),
  });
  const coupons = useInfiniteQuery({
    queryKey: ['coupons', 'me', 'pages', 'ALL', getToken()],
    initialPageParam: 0,
    queryFn: ({ pageParam }) => api<PageC<UserCoupon>>(`/api/coupons/me?size=50&page=${pageParam}`),
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
  });
  // REDIS 모드는 결제하기 때 좌석 선택 완료로 예약을 만든다. 한 화면에서 한 번만 부르고, 실패하면 다음 클릭에서 다시 부른다
  const knownReservationId = source.kind === 'reservation' ? Number(source.reservationId) : null;
  const resolveReservation = useMemo(
    () =>
      reservationResolver(knownReservationId, () => api<Hold>(`${holdPath}/reservation`, { method: 'POST' })),
    [knownReservationId, holdPath],
  );
  const convertedId = useRef<number | null>(null);
  const list = coupons.data?.pages.flatMap((page) => page.content) ?? [];
  const couponId = pickedCoupon === undefined ? (item?.userCouponId ?? null) : pickedCoupon;
  const choices = list.filter(
    (coupon) =>
      coupon.status === 'AVAILABLE' ||
      coupon.status === 'RESERVED' ||
      coupon.userCouponId === item?.userCouponId,
  );
  const coupon = choices.find((value) => value.userCouponId === couponId);
  const genre = holdMode ? hold.data?.genre : performance.data?.genre;
  const preview =
    coupon && item ? couponPreview(coupon, item.totalPrice, now, genre, item.userCouponId) : null;
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
  const pay = useMutation({
    mutationFn: async () => {
      const id = await resolveReservation();
      if (holdMode && convertedId.current === null) {
        convertedId.current = id;
        const label = seatLabel((source as { holdId: string }).holdId);
        if (label) saveSeatLabel(id, label);
        // 예약이 생겼으니 좌석 화면의 진행 중 선점 안내는 필요 없다(이후는 내 예매에 있다)
        forgetHold(source.kind === 'hold' ? source.scheduleId : '', (source as { holdId: string }).holdId);
        // 예약이 생겼으니 새로고침·다시 열기는 예약 결제 화면으로 가게 주소만 바꾼다. 화면은 다시 그리지 않아 결제가 이어진다.
        // 선점 기록은 원래 만료 시각에 사라져, 결제 중에 그 시각이 지나면 선점 주소는 "선점이 끝났어요"를 보이기 때문이다.
        // 라우터가 기록 항목에 둔 값(순번 등)은 그대로 둬 뒤로·앞으로 가기가 꼬이지 않게 한다
        window.history.replaceState(window.history.state, '', `/reservations/${id}/checkout`);
      }
      const path = `/api/reservations/${id}`;
      const result = await runPaymentAttempt(
        { couponId, expectedAmount, prepared: draft, authorization: authorized },
        {
          reservation: async () => {
            const latest = await api<Reservation>(path);
            client.setQueryData(reservationKey(id), latest);
            return latest;
          },
          prepare: (couponToUse) =>
            api<Checkout>(`${path}/payments/checkout`, {
              method: 'POST',
              body: { pgProvider: 'TOSS', ...(couponToUse === null ? {} : { userCouponId: couponToUse }) },
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
            client.setQueryData<Reservation>(reservationKey(id), (current) =>
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
      );
      return { id, result };
    },
    onSuccess: ({ id, result }) => {
      if (result.kind === 'prepared') {
        // 예약이 생긴 뒤라 예약 결제 화면에서 이어 간다. REDIS 모드에서는 화면을 옮겨 서버 금액으로 다시 보여 준다
        if (holdMode) {
          navigate(`/reservations/${id}/checkout`, { replace: true, state: { priceChanged: true } });
          return;
        }
        setPriceNotice(true);
        return;
      }
      setAuthorized(null);
      setPrepared(null);
      client.setQueryData(reservationKey(id), result.value);
      client.invalidateQueries({ queryKey: ['coupons'] });
      client.invalidateQueries({ queryKey: ['reservations'] });
      navigate(`/reservations/${id}`, { replace: true });
    },
    onError: (error) => {
      // 좌석 선택 완료 뒤 결제에서 멈췄으면 예약 결제 화면에서 이어 간다. 그 화면이 예약의 최신 상태로 다시 그린다
      if (holdMode && convertedId.current !== null) {
        const message =
          error instanceof CheckoutChangedError ? error.message : errorMessage(error);
        navigate(`/reservations/${convertedId.current}/checkout`, {
          replace: true,
          state: { message } satisfies CheckoutNotice,
        });
        return;
      }
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
      if (holdMode) hold.refetch();
      else reservation.refetch();
    },
    onSettled: () => {
      actionLock.current = false;
    },
  });
  // 좌석 선택 완료가 결과를 모른 채 끝나면 서버는 선점을 CONVERTING으로 둔다. 다시 부르면 만든 예약을 받거나 새로 만든다
  const resume = useMutation({
    mutationFn: () => resolveReservation(),
    onSuccess: (id) => navigate(`/reservations/${id}/checkout`, { replace: true }),
    onError: () => hold.refetch(),
  });
  const release = useMutation({
    mutationFn: () =>
      api(
        source.kind === 'hold'
          ? holdPath
          : `/api/seats/schedules/${item!.scheduleId}/holds/${source.reservationId}`,
        { method: 'DELETE' },
      ),
    onSuccess: () => {
      if (source.kind === 'hold') forgetHold(source.scheduleId, source.holdId);
      client.invalidateQueries({ queryKey: ['coupons'] });
      client.invalidateQueries({ queryKey: ['reservations'] });
      // 좌석을 놓은 뒤에는 다른 좌석을 고르러 좌석 화면으로 간다. 입장 시간이 끝났으면 좌석 화면이 대기열로 보낸다
      navigate(`/performances/${item!.performanceId}/schedules/${item!.scheduleId}/seats`, { replace: true });
    },
    onError: () => (holdMode ? hold.refetch() : reservation.refetch()),
    onSettled: () => {
      actionLock.current = false;
    },
  });
  // 끝난 선점(404)은 좌석 화면의 진행 중 선점 안내에서도 뺀다
  const holdGone = hold.error instanceof ApiError && hold.error.code === 'HOLD_NOT_FOUND';
  useEffect(() => {
    if (holdGone && source.kind === 'hold') forgetHold(source.scheduleId, source.holdId);
  }, [holdGone, source]);
  const source$ = holdMode ? hold : reservation;
  if (source$.isPending)
    return (
      <main className="content">
        <Loading />
      </main>
    );
  if (holdMode && hold.error instanceof ApiError && hold.error.code === 'HOLD_NOT_FOUND') {
    // 실패한 조회도 마지막으로 받은 선점은 남아 있어 그 회차의 좌석 화면으로 보낸다
    const last = hold.data;
    const cause = pay.error instanceof ApiError ? pay.error.code : '';
    const [title, body] =
      cause === 'SEAT_ALREADY_TAKEN'
        ? ['다른 분이 먼저 예매한 좌석이에요', '결제 직전에 좌석을 한 번 더 확인하는데, 그사이 다른 분의 예매가 먼저 끝났어요. 결제는 진행되지 않았어요.']
        : cause && cause !== 'HOLD_NOT_FOUND' && cause !== 'HOLD_EXPIRED'
          ? ['이 좌석은 예매할 수 없어요', errorMessage(pay.error)]
          : ['좌석을 잡아 둔 시간이 끝났어요', '선점은 10분 동안 유지돼요. 결제는 진행되지 않았어요.'];
    const seat = last ? seatLabel(last.holdId) : null;
    return (
      <main className="content">
        <section className="hold-ended" role="alert">
          <span className="hold-ended-mark" aria-hidden="true">
            !
          </span>
          <h1>{title}</h1>
          <p>{body}</p>
          {last && (
            <dl className="hold-ended-item">
              <div>
                <dt>공연</dt>
                <dd>{last.performanceTitle}</dd>
              </div>
              <div>
                <dt>일시</dt>
                <dd>{formatDateTime(last.scheduleStartAt)}</dd>
              </div>
              <div>
                <dt>좌석</dt>
                <dd>{seat ?? `${last.gradeName} 좌석`}</dd>
              </div>
            </dl>
          )}
          <div className="hold-ended-actions">
            {last ? (
              <Link
                className="btn btn-primary"
                to={`/performances/${last.performanceId}/schedules/${last.scheduleId}/seats`}
              >
                다른 좌석 고르기
              </Link>
            ) : (
              <Link className="btn btn-primary" to="/">
                공연 둘러보기
              </Link>
            )}
            <Link className="btn btn-ghost" to="/reservations">
              내 예매
            </Link>
          </div>
        </section>
      </main>
    );
  }
  if (source$.isError || !item)
    return (
      <main className="content">
        <ErrorNotice error={source$.error} />
        <button type="button" className="btn btn-ghost" onClick={() => source$.refetch()}>
          {holdMode ? '선점 다시 확인' : '예매 다시 확인'}
        </button>
      </main>
    );
  if (holdMode) {
    // 이미 예약으로 바뀐 선점(다른 창·새로고침)은 예약 결제 화면에서 이어 간다
    if (hold.data!.status === 'CONVERTED' && hold.data!.reservationId !== null && !pay.isPending)
      return <Navigate to={`/reservations/${hold.data!.reservationId}/checkout`} replace />;
    if (hold.data!.status === 'CONVERTING' && !pay.isPending)
      return (
        <main className="content">
          <p className="notice">좌석 선택 완료 결과를 확인하지 못했어요. 같은 선점으로 다시 확인해 주세요.</p>
          {resume.isError && <ErrorNotice error={resume.error} />}
          <button
            type="button"
            className="btn btn-primary"
            disabled={resume.isPending}
            onClick={() => resume.mutate()}
          >
            {resume.isPending ? '확인하고 있어요' : '좌석 선택 완료 다시 확인'}
          </button>
        </main>
      );
  } else if (reservation.data!.status !== 'HOLD')
    return <Navigate to={`/reservations/${item.reservationId}`} replace />;
  const seatsPath = `/performances/${item.performanceId}/schedules/${item.scheduleId}/seats`;
  const reselect =
    holdMode && pay.error instanceof ApiError && RESELECT_CODES.has(pay.error.code);
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
          {reselect && (
            <>
              {' '}
              <Link to={seatsPath}>좌석 다시 고르기</Link>
            </>
          )}
        </div>
      )}
      {notice?.message && !pay.isError && (
        <div className="checkout-alert" role="alert">
          좌석 선택은 끝났고 결제에서 멈췄어요. {notice.message}
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
                <p>{item.seat}</p>
                {item.reservationId !== null && (
                  <p className="checkout-reference">예매 번호 {item.reservationId}</p>
                )}
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
                  genre,
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
              <dt>티켓 금액 ({item.ticketCount}매)</dt>
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
            <Link
              className="btn btn-ghost btn-block"
              to={holdMode ? seatsPath : `/reservations/${item.reservationId}`}
            >
              {holdMode ? '좌석 다시 고르기' : '예매 상태 확인'}
            </Link>
          )}
          <div className="checkout-payment-note">
            <h3>예매·결제 안내</h3>
            <p>결제 결과를 확인 중일 때에는 새 결제를 만들지 않고 같은 요청으로 확인해요.</p>
            <p>좌석을 놓고 나가면 현재 선점이 해제되고 좌석 선택으로 돌아가요.</p>
          </div>
        </aside>
      </div>
    </main>
  );
}
