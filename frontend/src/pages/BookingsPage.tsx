import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { AccountNav } from '../components/AccountNav';
import { api, getToken } from '../api/client';
import type { PageC, ReservationSummary } from '../api/types';
import { BookingCard } from '../components/BookingCard';
import { ErrorNotice, Loading } from '../components/Common';
import { BOOKING_FILTERS, bookingPending } from '../lib/bookings';

export function BookingsPage() {
  const [params, setParams] = useSearchParams();
  const status = BOOKING_FILTERS.find((value) => value.value === params.get('status'))?.value ?? '';
  const value = Number(params.get('page') ?? 0);
  const page = Number.isSafeInteger(value) && value >= 0 ? value : 0;
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    window.scrollTo({ top: 0 });
  }, [page]);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const request = new URLSearchParams({ page: String(page), size: '10' });
  if (status) request.set('status', status);
  const query = useQuery({
    queryKey: ['reservations', getToken(), status, page],
    queryFn: () => api<PageC<ReservationSummary>>(`/api/reservations?${request}`),
    refetchOnWindowFocus: true,
    refetchInterval: (query) =>
      query.state.data?.content.some((item) => bookingPending(item.status)) ? 5000 : false,
  });
  const change = (nextStatus: string, nextPage: number) => {
    const next = new URLSearchParams();
    if (nextStatus) next.set('status', nextStatus);
    if (nextPage) next.set('page', String(nextPage));
    setParams(next, { preventScrollReset: true });
  };
  return (
    <main className="content my-bookings-content">
      <div className="my-bookings-layout">
        <AccountNav />
        <div className="my-bookings-main">
          <div className="my-bookings-heading">
            <h1 className="section-marker">내 예매</h1>
            <button
              type="button"
              className="booking-refresh"
              disabled={query.isFetching}
              onClick={() => query.refetch()}
            >
              내역 새로고침 ↻
            </button>
          </div>
          <div className="booking-filters" aria-label="예매 상태">
            {BOOKING_FILTERS.map((filter) => (
              <button
                type="button"
                key={filter.value}
                className="chip"
                aria-pressed={filter.value === status}
                onClick={() => change(filter.value, 0)}
              >
                {filter.label}
              </button>
            ))}
          </div>
          <div className="my-bookings-caption">
            <p>만료·선점 해제 내역은 해당 필터에서 확인할 수 있어요.</p>
            {query.data && <span>총 {query.data.totalElements}건</span>}
          </div>
          {query.isPending && <Loading />}
          {query.isError && (
            <div className="stack">
              <ErrorNotice error={query.error} />
              <button type="button" className="btn btn-ghost" onClick={() => query.refetch()}>
                내역 다시 불러오기
              </button>
            </div>
          )}
          {query.data?.content.length === 0 && (
            <div className="my-bookings-empty">
              <h2>해당하는 예매 내역이 없어요</h2>
              <p>보고 싶은 공연을 찾아 예매해 보세요.</p>
              <Link className="btn btn-primary" to="/">
                공연 보러 가기
              </Link>
            </div>
          )}
          <div className="my-booking-list" aria-busy={query.isFetching}>
            {query.data?.content.map((item) => (
              <BookingCard
                key={item.reservationId}
                item={item}
                now={now}
                listUpdatedAt={query.dataUpdatedAt}
              />
            ))}
          </div>
          {query.data && query.data.totalPages > 1 && (
            <nav className="pager" aria-label="예매 내역 페이지">
              <button
                type="button"
                className="chip"
                disabled={page === 0 || query.isFetching}
                onClick={() => change(status, page - 1)}
              >
                이전
              </button>
              <span>
                {page + 1} / {query.data.totalPages}
              </span>
              <button
                type="button"
                className="chip"
                disabled={page + 1 >= query.data.totalPages || query.isFetching}
                onClick={() => change(status, page + 1)}
              >
                다음
              </button>
            </nav>
          )}
        </div>
      </div>
    </main>
  );
}
