import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, getToken } from '../api/client';
import type { PerformanceDetail, Reservation, ReservationSummary } from '../api/types';
import { BOOKING_COPY, canResumeBooking } from '../lib/bookings';
import { clock, formatDateTime, parseTime, won } from '../lib/format';
import { seatLabel } from '../lib/seatLabels';
import { Poster } from './Common';

export function BookingCard({
  item,
  now,
  listUpdatedAt,
}: {
  item: ReservationSummary;
  now: number;
  listUpdatedAt: number;
}) {
  const ref = useRef<HTMLElement>(null);
  const [seen, setSeen] = useState(false);
  useEffect(() => {
    if (!('IntersectionObserver' in window)) {
      setSeen(true);
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setSeen(true);
          observer.disconnect();
        }
      },
      { rootMargin: '240px' },
    );
    if (ref.current) observer.observe(ref.current);
    return () => observer.disconnect();
  }, []);
  const detail = useQuery({
    queryKey: ['reservation', String(item.reservationId), getToken()],
    enabled: seen,
    queryFn: () => api<Reservation>(`/api/reservations/${item.reservationId}`),
    staleTime: 30_000,
  });
  const performance = useQuery({
    queryKey: ['performance', String(detail.data?.performanceId)],
    enabled: seen && detail.data !== undefined,
    queryFn: () => api<PerformanceDetail>(`/api/performances/${detail.data!.performanceId}`),
    staleTime: 60_000,
  });
  const state = detail.data && detail.dataUpdatedAt >= listUpdatedAt ? detail.data.status : item.status;
  const copy = BOOKING_COPY[state];
  const resume = canResumeBooking(state, detail.data?.holdExpiresAt, now);
  const seconds = detail.data
    ? Math.max(0, Math.floor((parseTime(detail.data.holdExpiresAt).getTime() - now) / 1000))
    : null;
  return (
    <article ref={ref} className="my-booking-card">
      <div className="my-booking-poster">
        <Poster src={performance.data?.posterUrl ?? null} />
        <span aria-hidden="true">safe ticket</span>
      </div>
      <div className="my-booking-info">
        <span className={`booking-state ${copy.tone}`}>{copy.label}</span>
        <h2>
          <Link to={`/reservations/${item.reservationId}`}>{item.performanceTitle}</Link>
        </h2>
        <p>{formatDateTime(item.scheduleStartAt)}</p>
        {performance.data && <p>{performance.data.venue.name}</p>}
        <p>{seatLabel(item.reservationId) ?? `좌석 ${item.seatCount}장`}</p>
        <span className="my-booking-reference">
          예매 번호 {item.reservationId} · {formatDateTime(item.createdAt)} 예매
        </span>
      </div>
      <div className="my-booking-actions">
        {item.paymentAmount !== null && (
          <strong className="my-booking-amount">{won(item.paymentAmount)}</strong>
        )}
        {state === 'HOLD' && seconds !== null && (
          <span className={`my-booking-time ${seconds <= 120 ? 'urgent' : ''}`}>
            {resume ? `결제 가능 시간 ${clock(seconds)}` : '선점 시간 종료 · 상태 확인 필요'}
          </span>
        )}
        {resume && (
          <Link className="btn btn-primary" to={`/reservations/${item.reservationId}/checkout`}>
            결제 이어하기
          </Link>
        )}
        <Link className="btn btn-ghost" to={`/reservations/${item.reservationId}`}>
          예매 상세 ›
        </Link>
      </div>
    </article>
  );
}
