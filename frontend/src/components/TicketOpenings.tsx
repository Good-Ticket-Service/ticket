import { useQueries } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { PerformanceSummary, Schedule } from '../api/types';
import { formatDateTime, parseTime } from '../lib/format';
import { ErrorNotice, Loading, Poster } from './Common';

const dateKey = (date: Date) => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul' }).format(date);
const dayLabel = (date: Date) =>
  new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: 'numeric',
    day: 'numeric',
    weekday: 'short',
  }).format(date);

export function TicketOpenings({
  items,
  pending,
  error,
}: {
  items: PerformanceSummary[];
  pending: boolean;
  error: unknown;
}) {
  const [today] = useState(() => new Date(`${dateKey(new Date())}T00:00:00+09:00`));
  const [offset, setOffset] = useState(0);
  const [picked, setPicked] = useState(dateKey(today));
  const queries = useQueries({
    queries: items.map((item) => ({
      queryKey: ['schedules', String(item.id)],
      queryFn: () => api<{ schedules: Schedule[] }>(`/api/performances/${item.id}/schedules`),
      staleTime: 60_000,
    })),
  });
  const days = Array.from(
    { length: 5 },
    (_, index) => new Date(today.getTime() + (offset + index) * 86_400_000),
  );
  const entries = queries
    .flatMap((query, index) =>
      (query.data?.schedules ?? [])
        .filter(
          (schedule) =>
            schedule.salesStatus !== 'CANCELED' &&
            !schedule.salesSuspended &&
            dateKey(parseTime(schedule.bookingOpenAt)) === picked,
        )
        .map((schedule) => ({ schedule, item: items[index] })),
    )
    .sort(
      (a, b) => parseTime(a.schedule.bookingOpenAt).getTime() - parseTime(b.schedule.bookingOpenAt).getTime(),
    )
    .filter(
      (entry, index, all) =>
        all.findIndex(
          (other) =>
            other.item.id === entry.item.id && other.schedule.bookingOpenAt === entry.schedule.bookingOpenAt,
        ) === index,
    );
  const loading = pending || queries.some((query) => query.isPending);
  const failed = error ?? queries.find((query) => query.isError)?.error;
  const move = (direction: number) => {
    const next = Math.max(0, offset + direction * 5);
    setOffset(next);
    setPicked(dateKey(new Date(today.getTime() + next * 86_400_000)));
  };
  return (
    <section id="ticket-openings" className="home-section" aria-labelledby="openings-title">
      <div className="home-section-heading">
        <div>
          <h2 id="openings-title">티켓 오픈 캘린더</h2>
          <p>메인에 소개된 공연의 예매 오픈 일정이에요.</p>
        </div>
        <div className="calendar-arrows">
          <button type="button" aria-label="이전 5일" disabled={offset === 0} onClick={() => move(-1)}>
            ‹
          </button>
          <button type="button" aria-label="다음 5일" onClick={() => move(1)}>
            ›
          </button>
        </div>
      </div>
      <div className="opening-days" aria-label="오픈 날짜 선택">
        {days.map((day) => (
          <button
            type="button"
            key={dateKey(day)}
            aria-pressed={picked === dateKey(day)}
            onClick={() => setPicked(dateKey(day))}
          >
            {dateKey(day) === dateKey(today) && <span>오늘 </span>}
            {dayLabel(day)}
          </button>
        ))}
      </div>
      {loading && <Loading />}
      {failed != null && <ErrorNotice error={failed} />}
      {!loading && !failed && entries.length === 0 && (
        <p className="home-empty">이 날짜에 오픈하는 소개 공연이 없어요.</p>
      )}
      <div className="opening-list">
        {entries.map(({ schedule, item }) => (
          <Link key={schedule.id} to={`/performances/${item.id}`} className="opening-row">
            <time className="opening-time num" dateTime={schedule.bookingOpenAt}>
              {new Intl.DateTimeFormat('ko-KR', {
                timeZone: 'Asia/Seoul',
                hour: '2-digit',
                minute: '2-digit',
                hour12: false,
              }).format(parseTime(schedule.bookingOpenAt))}
            </time>
            <span className="opening-poster">
              <Poster src={item.posterUrl} />
            </span>
            <span className="opening-copy">
              <strong>{item.title}</strong>
              <span>
                {item.venue.name} <span aria-hidden="true">·</span> 관람 {formatDateTime(schedule.startAt)}
              </span>
            </span>
            <span className="opening-detail">
              상세 보기 <span aria-hidden="true">↗</span>
            </span>
          </Link>
        ))}
      </div>
    </section>
  );
}
