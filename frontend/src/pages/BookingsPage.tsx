import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { PageC, ReservationStatus, ReservationSummary } from '../api/types';
import { ErrorNotice, Loading, STATUS_LABEL, Title } from '../components/Common';
import { formatDateTime, won } from '../lib/format';

const FILTERS: { value: ReservationStatus | ''; label: string }[] = [
  { value: '', label: '전체' },
  { value: 'CONFIRMED', label: '예매 완료' },
  { value: 'CANCELED', label: '취소' },
];

export function BookingsPage() {
  const [status, setStatus] = useState<ReservationStatus | ''>('');
  const [page, setPage] = useState(0);
  const params = new URLSearchParams({ page: String(page), size: '10' });
  if (status) params.set('status', status);
  const query = useQuery({
    queryKey: ['reservations', params.toString()],
    queryFn: () => api<PageC<ReservationSummary>>(`/api/reservations?${params}`),
  });
  return (
    <main className="content medium">
      <Title en="My Tickets" ko="내 예매" />
      <div className="chips" style={{ marginBottom: 20 }}>
        {FILTERS.map((filter) => (
          <button
            key={filter.value}
            type="button"
            className="chip"
            aria-pressed={status === filter.value}
            onClick={() => {
              setStatus(filter.value);
              setPage(0);
            }}
          >
            {filter.label}
          </button>
        ))}
      </div>
      {query.isPending && <Loading />}
      {query.isError && <ErrorNotice error={query.error} />}
      {query.data?.content.length === 0 && (
        <p className="empty">
          예매 내역이 없어요. <Link to="/">공연 보러 가기</Link>
        </p>
      )}
      <div className="stack">
        {query.data?.content.map((item) => {
          const to = item.status === 'HOLD' ? `/reservations/${item.reservationId}/checkout` : `/reservations/${item.reservationId}`;
          return (
            <Link key={item.reservationId} to={to} className="booking">
              <span>
                <span className="name">{item.performanceTitle}</span>
                <br />
                <span className="muted num" style={{ fontSize: 14 }}>
                  {formatDateTime(item.scheduleStartAt)} · 좌석 {item.seatCount}장
                  {item.paymentAmount !== null && ` · ${won(item.paymentAmount)}`}
                </span>
              </span>
              <span className={item.status === 'CONFIRMED' ? 'pill on' : 'pill'}>{STATUS_LABEL[item.status]}</span>
            </Link>
          );
        })}
      </div>
      {query.data && query.data.totalPages > 1 && (
        <div className="pager">
          <button type="button" className="chip" disabled={page === 0} onClick={() => setPage(page - 1)}>
            이전
          </button>
          <span className="num">
            {page + 1} / {query.data.totalPages}
          </span>
          <button type="button" className="chip" disabled={page + 1 >= query.data.totalPages} onClick={() => setPage(page + 1)}>
            다음
          </button>
        </div>
      )}
    </main>
  );
}
