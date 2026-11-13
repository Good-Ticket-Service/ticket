import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError, apiWithRetryAfter, getToken } from '../api/client';
import type { PerformanceDetail, Reservation, ReservationStatus } from '../api/types';
import { CancelBookingDialog } from '../components/CancelBookingDialog';
import { ErrorNotice, Loading, Poster } from '../components/Common';
import {
  BOOKING_COPY,
  bookingPending,
  bookingPollInterval,
  bookingTicketCodes,
  canCancelBooking,
  canResumeBooking,
} from '../lib/bookings';
import { clock, formatDateTime, parseTime, won } from '../lib/format';
import { seatLabel } from '../lib/seatLabels';

export function BookingDetailPage() {
  const { reservationId } = useParams();
  if (!reservationId || !/^[1-9]\d*$/.test(reservationId))
    return (
      <main className="content">
        <p className="notice">올바른 예매를 선택해 주세요.</p>
        <Link to="/reservations">내 예매 목록</Link>
      </main>
    );
  return <BookingDetail key={reservationId} reservationId={reservationId} />;
}
function BookingDetail({ reservationId }: { reservationId: string }) {
  const client = useQueryClient();
  const [asking, setAsking] = useState(false);
  const [now, setNow] = useState(Date.now);
  const [copied, setCopied] = useState<number | null>(null);
  const [copyError, setCopyError] = useState(false);
  const cancelLock = useRef(false);
  useEffect(() => {
    window.scrollTo({ top: 0 });
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const path = `/api/reservations/${reservationId}`;
  const queryKey = ['reservation', reservationId, getToken()];
  const query = useQuery({ queryKey, queryFn: () => api<Reservation>(path), refetchOnWindowFocus: true });
  const item = query.data;
  const poll = !!item && (bookingPending(item.status) || item.status === 'HOLD');
  const status = useQuery({
    queryKey: ['reservation-status', reservationId, getToken()],
    enabled: poll,
    queryFn: ({ signal }) =>
      apiWithRetryAfter<{ reservationId: number; status: ReservationStatus }>(`${path}/status`, {
        cache: 'no-store',
        signal,
      }),
    refetchInterval: (state) =>
      state.state.status === 'error'
        ? false
        : state.state.data
          ? bookingPollInterval(state.state.data.data.status, state.state.data.retryAfter, Date.now())
          : 3000,
    retry: false,
    gcTime: 0,
  });
  useEffect(() => {
    if (
      poll &&
      status.data &&
      status.dataUpdatedAt > query.dataUpdatedAt &&
      status.data.data.status !== item?.status
    )
      query.refetch();
  }, [poll, status.data, status.dataUpdatedAt, query.dataUpdatedAt, item?.status]);
  const performance = useQuery({
    queryKey: ['performance', String(item?.performanceId)],
    enabled: item !== undefined,
    queryFn: () => api<PerformanceDetail>(`/api/performances/${item!.performanceId}`),
    staleTime: 60000,
  });
  const cancel = useMutation({
    mutationFn: async () => {
      const latest = await api<Reservation>(path);
      client.setQueryData(queryKey, latest);
      if (latest.status === 'CANCELING' || latest.status === 'CANCELED') return latest;
      if (latest.status !== 'CONFIRMED')
        throw new ApiError(
          409,
          'INVALID_STATUS_TRANSITION',
          '예매 상태가 변경됐어요. 최신 상태를 확인해 주세요.',
        );
      if (!canCancelBooking(latest.status, latest.scheduleStartAt, Date.now()))
        throw new ApiError(409, 'NOT_CANCELLABLE', '취소 가능한 시간이 지났어요.');
      return api<Reservation>(`${path}/cancel`, { method: 'POST', body: {} });
    },
    onSuccess: (result) => {
      client.setQueryData(queryKey, result);
      client.invalidateQueries({ queryKey: ['reservations'] });
      client.invalidateQueries({ queryKey: ['coupons'] });
      setAsking(false);
    },
    onError: () => query.refetch(),
    onSettled: () => {
      cancelLock.current = false;
    },
  });
  useEffect(() => {
    if (asking && !cancel.isPending && (item?.status === 'CANCELING' || item?.status === 'CANCELED'))
      setAsking(false);
  }, [asking, cancel.isPending, item?.status]);
  if (query.isPending)
    return (
      <main className="content">
        <Loading />
      </main>
    );
  if (!item)
    return (
      <main className="content">
        <ErrorNotice error={query.error} />
        <button type="button" className="btn btn-ghost" onClick={() => query.refetch()}>
          예매 다시 불러오기
        </button>
      </main>
    );
  const copy = BOOKING_COPY[item.status];
  const cancellable = canCancelBooking(item.status, item.scheduleStartAt, now);
  const resume = canResumeBooking(item.status, item.holdExpiresAt, now);
  const seconds = Math.max(0, Math.floor((parseTime(item.holdExpiresAt).getTime() - now) / 1000));
  const tickets = bookingTicketCodes(item);
  const copyTicket = async (id: number, code: string) => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(id);
      setCopyError(false);
    } catch {
      setCopyError(true);
    }
  };
  return (
    <main className="content booking-detail-content">
      <div className="booking-detail-heading">
        <div>
          <Link className="booking-detail-back" to="/reservations">
            ‹ 내 예매 목록
          </Link>
          <h1 className="section-marker">예매 상세</h1>
        </div>
        <button
          type="button"
          className="booking-refresh"
          disabled={query.isFetching || status.isFetching || cancel.isPending}
          onClick={() => {
            query.refetch();
            if (poll) status.refetch();
          }}
        >
          최신 상태 확인 ↻
        </button>
      </div>
      <section className={`booking-status-panel ${copy.tone}`} aria-labelledby="booking-result-title">
        <span className={`booking-state ${copy.tone}`}>{copy.label}</span>
        <h2 id="booking-result-title" aria-live="polite">
          {item.status === 'HOLD' && !resume
            ? '선점 시간이 끝났어요. 최신 상태를 확인해 주세요.'
            : copy.title}
        </h2>
        <p>{copy.body}</p>
        {item.status === 'HOLD' && (
          <div
            className={`booking-hold-clock ${seconds <= 120 ? 'urgent' : ''}`}
            role="timer"
            aria-label="결제 가능한 남은 시간"
          >
            남은 시간 <strong>{clock(seconds)}</strong>
          </div>
        )}
        <div className="booking-result-actions">
          {resume && (
            <Link className="btn btn-primary" to={`/reservations/${reservationId}/checkout`}>
              결제 이어하기
            </Link>
          )}
          {(item.status === 'EXPIRED' || item.status === 'RELEASED') && (
            <Link
              className="btn btn-primary"
              to={`/performances/${item.performanceId}/queue?scheduleId=${item.scheduleId}`}
            >
              좌석 다시 선택
            </Link>
          )}
          {item.status === 'CANCELED' && (
            <Link className="btn btn-primary" to="/">
              공연 보러 가기
            </Link>
          )}
          {cancellable && (
            <button
              type="button"
              className="btn btn-ghost"
              onClick={() => {
                cancel.reset();
                setAsking(true);
              }}
            >
              예매 취소
            </button>
          )}
        </div>
      </section>
      {query.isError && <ErrorNotice error={query.error} />}
      {poll && status.isError && (
        <div className="booking-status-error">
          <ErrorNotice error={status.error} />
          <button type="button" className="booking-refresh" onClick={() => status.refetch()}>
            상태 다시 확인
          </button>
        </div>
      )}
      <div className="booking-detail-grid">
        <div className="booking-detail-main">
          <section className="booking-detail-panel">
            <h2>예매 정보</h2>
            <div className="booking-detail-product">
              <div className="booking-detail-poster">
                <Poster src={performance.data?.posterUrl ?? null} />
                <span aria-hidden="true">safe ticket</span>
              </div>
              <div>
                <h3>{item.performanceTitle}</h3>
                <p>{formatDateTime(item.scheduleStartAt)}</p>
                {performance.data && <p>{performance.data.venue.name}</p>}
                <p>{seatLabel(item.reservationId) ?? `좌석 ${item.tickets.length}장`}</p>
                <Link to={`/performances/${item.performanceId}`}>공연 상세 보기 ↗</Link>
              </div>
            </div>
            <dl className="booking-detail-meta">
              <div>
                <dt>예매 번호</dt>
                <dd>{item.reservationId}</dd>
              </div>
              <div>
                <dt>예매 상태</dt>
                <dd>{copy.label}</dd>
              </div>
              <div>
                <dt>선택 좌석</dt>
                <dd>{seatLabel(item.reservationId) ?? `${item.tickets.length}장`}</dd>
              </div>
            </dl>
          </section>
          {item.status === 'CONFIRMED' && (
            <section className="booking-detail-panel">
              <h2>티켓 코드</h2>
              {tickets.length === 0 ? (
                <p className="booking-detail-note">
                  아직 표시할 티켓 코드가 없어요. 최신 상태를 다시 확인해 주세요.
                </p>
              ) : (
                tickets.map((ticket) => (
                  <div key={ticket.scheduleSeatId} className="booking-ticket-code">
                    <span>티켓 {ticket.scheduleSeatId}</span>
                    <code>{ticket.ticketCode}</code>
                    <button
                      type="button"
                      className="btn btn-ghost"
                      onClick={() => copyTicket(ticket.scheduleSeatId, ticket.ticketCode!)}
                    >
                      {copied === ticket.scheduleSeatId ? '복사됨' : '코드 복사'}
                    </button>
                  </div>
                ))
              )}
              {copyError && (
                <p role="status" className="booking-detail-note">
                  복사하지 못했어요. 티켓 코드를 직접 선택해서 복사해 주세요.
                </p>
              )}
            </section>
          )}
        </div>
        <aside className="booking-detail-panel booking-payment-summary">
          <h2>결제 내역</h2>
          <dl>
            <div>
              <dt>티켓 금액</dt>
              <dd>{won(item.totalPrice)}</dd>
            </div>
            <div>
              <dt>쿠폰 할인</dt>
              <dd>{item.discountAmount ? `−${won(item.discountAmount)}` : '0원'}</dd>
            </div>
            {item.paymentAmount !== null && (
              <div className="booking-payment-total">
                <dt>
                  {item.status === 'CONFIRMED'
                    ? '결제 금액'
                    : item.status === 'CANCELED' || item.status === 'CANCELING'
                      ? '기존 결제 금액'
                      : '준비된 결제 금액'}
                </dt>
                <dd>{won(item.paymentAmount)}</dd>
              </div>
            )}
          </dl>
          {item.status === 'PAYMENT_IN_PROGRESS' && (
            <p className="booking-detail-note">결제 결과가 확인되면 이 화면이 갱신돼요.</p>
          )}
          {(item.status === 'CANCELED' || item.status === 'CANCELING') && (
            <p className="booking-detail-note">
              표시한 금액은 예매의 결제 내역이에요. PG 환불 완료와 카드·계좌 반영 시점은 다를 수 있어요.
            </p>
          )}
          {item.status === 'CONFIRMED' && (
            <p className="booking-detail-note">
              {cancellable
                ? `${formatDateTime(item.scheduleStartAt)} 공연 시작 전까지 취소를 요청할 수 있어요.`
                : '공연 시작 시각이 지나 취소할 수 없어요.'}
            </p>
          )}
          <Link className="booking-detail-coupon-link" to="/coupons">
            쿠폰함 확인 ↗
          </Link>
        </aside>
      </div>
      {asking && (
        <CancelBookingDialog
          title={item.performanceTitle}
          pending={cancel.isPending}
          allowed={cancellable}
          error={cancel.error}
          onDismiss={() => {
            if (!cancel.isPending) setAsking(false);
          }}
          onConfirm={() => {
            if (cancelLock.current || !cancellable) return;
            cancelLock.current = true;
            cancel.mutate();
          }}
        />
      )}
    </main>
  );
}
