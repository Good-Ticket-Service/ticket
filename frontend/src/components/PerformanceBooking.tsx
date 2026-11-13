import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { PerformanceDetail, RemainingGrade, Schedule } from '../api/types';
import {
  calendarDate,
  canBook,
  initialCalendarSelection,
  monthCells,
  schedulesByDate,
  shiftCalendarMonth,
} from '../lib/performanceCalendar';
import { formatDateTime, parseTime, won } from '../lib/format';
import { SALES_STATUS_LABEL } from '../lib/labels';
import { entryToken } from '../lib/entryTokens';
import { ErrorNotice, Loading } from './Common';
import { useMe } from './Layout';

function useBookingClock(schedules: Schedule[], refresh: () => void) {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const next = schedules.reduce((nearest, schedule) => {
      for (const boundary of [schedule.bookingOpenAt, schedule.bookingCloseAt]) {
        const time = parseTime(boundary).getTime();
        if (time > now) nearest = Math.min(nearest, time);
      }
      return nearest;
    }, now + 60_000);
    const update = () => {
      setNow(Date.now());
      refresh();
    };
    const timer = setTimeout(update, Math.max(1, next - now + 10));
    window.addEventListener('focus', update);
    return () => {
      clearTimeout(timer);
      window.removeEventListener('focus', update);
    };
  }, [schedules, now, refresh]);
  return now;
}

export function PerformanceBooking({
  item,
  schedules,
  refresh,
}: {
  item: PerformanceDetail;
  schedules: Schedule[];
  refresh: () => void;
}) {
  const now = useBookingClock(schedules, refresh);
  const me = useMe();
  const [selection, setSelection] = useState(() => initialCalendarSelection(schedules, now));
  const days = useMemo(() => schedulesByDate(schedules), [schedules]);
  const currentMonth = calendarDate(now).slice(0, 7);
  const lastMonth = [...days.keys()].reduce(
    (last, day) => (day.slice(0, 7) > last ? day.slice(0, 7) : last),
    currentMonth,
  );
  const daySchedules = days.get(selection.date) ?? [];
  const selected = daySchedules.find(
    (schedule) => schedule.id === selection.scheduleId && canBook(schedule, now),
  );
  const remaining = useQuery({
    queryKey: ['remaining', selected?.id],
    enabled: selected !== undefined,
    queryFn: () => api<{ grades: RemainingGrade[] }>(`/api/seats/schedules/${selected!.id}/remaining`),
  });
  const soldOut =
    remaining.isSuccess &&
    remaining.data.grades.length > 0 &&
    remaining.data.grades.every((grade) => grade.remaining === 0);
  const member = !me.data || me.data.role === 'USER';
  const busyAccount = me.isFetching || me.isError;
  const chooseDate = (date: string) =>
    setSelection({
      month: selection.month,
      date,
      scheduleId: days.get(date)?.find((schedule) => canBook(schedule, now))?.id ?? null,
    });
  const moveMonth = (delta: number) =>
    setSelection({ month: shiftCalendarMonth(selection.month, delta), date: '', scheduleId: null });
  const bookableDate = (date: string) =>
    date >= calendarDate(now) &&
    (days.get(date) ?? []).some(
      (schedule) =>
        !schedule.canceledAt &&
        !schedule.salesSuspended &&
        parseTime(schedule.startAt).getTime() > now &&
        (canBook(schedule, now) || schedule.salesStatus === 'BEFORE_OPEN'),
    );
  const next = selected
    ? entryToken(selected.id)
      ? `/performances/${item.id}/schedules/${selected.id}/seats`
      : `/performances/${item.id}/queue?scheduleId=${selected.id}`
    : '';
  const buttonLabel = !member
    ? '일반 회원만 예매할 수 있어요'
    : !selected
      ? '예매할 회차를 선택해 주세요'
      : soldOut
        ? '선택한 회차는 매진됐어요'
        : '예매하기';

  return (
    <aside className="performance-booking" aria-labelledby="booking-heading">
      <h2 id="booking-heading">예매하기</h2>
      <section aria-labelledby="calendar-heading">
        <h3 id="calendar-heading">관람일</h3>
        <div className="performance-calendar">
          <div className="calendar-month">
            <button
              type="button"
              aria-label="이전 달"
              disabled={selection.month <= currentMonth}
              onClick={() => moveMonth(-1)}
            >
              ‹
            </button>
            <strong aria-live="polite">
              {selection.month.slice(0, 4)}년 {Number(selection.month.slice(5))}월
            </strong>
            <button
              type="button"
              aria-label="다음 달"
              disabled={selection.month >= lastMonth}
              onClick={() => moveMonth(1)}
            >
              ›
            </button>
          </div>
          <div className="calendar-weekdays" aria-hidden="true">
            {['일', '월', '화', '수', '목', '금', '토'].map((day) => (
              <span key={day}>{day}</span>
            ))}
          </div>
          <div className="calendar-dates" aria-label="관람 날짜 선택">
            {monthCells(selection.month).map((date, index) =>
              date ? (
                <button
                  type="button"
                  key={date}
                  disabled={!bookableDate(date)}
                  aria-label={`${date}${date === calendarDate(now) ? ', 오늘' : ''}${bookableDate(date) ? ', 회차 있음' : ', 예매 불가'}`}
                  aria-pressed={selection.date === date}
                  onClick={() => chooseDate(date)}
                >
                  <span>{Number(date.slice(-2))}</span>
                  {bookableDate(date) && <i aria-hidden="true" />}
                </button>
              ) : (
                <span key={`blank-${index}`} />
              ),
            )}
          </div>
        </div>
        <p className="calendar-help">표시된 날짜를 선택하면 관람 시간을 확인할 수 있어요.</p>
      </section>
      <section aria-labelledby="time-heading">
        <h3 id="time-heading">관람 시간</h3>
        {!selection.date && <p className="booking-hint">관람 날짜를 선택해 주세요.</p>}
        {selection.date && daySchedules.length === 0 && (
          <p className="booking-hint">이 날짜에는 등록된 회차가 없어요.</p>
        )}
        <div className="performance-times">
          {daySchedules.map((schedule) => {
            const open = canBook(schedule, now);
            const closed = parseTime(schedule.bookingCloseAt).getTime() <= now;
            const status =
              schedule.canceledAt || schedule.salesStatus === 'CANCELED'
                ? '회차 취소'
                : schedule.salesSuspended
                  ? '판매 중지'
                  : closed
                    ? '예매 종료'
                    : SALES_STATUS_LABEL[schedule.salesStatus];
            return (
              <button
                key={schedule.id}
                type="button"
                disabled={!open}
                aria-pressed={selected?.id === schedule.id}
                onClick={() => setSelection({ ...selection, scheduleId: schedule.id })}
              >
                <strong>{formatDateTime(schedule.startAt).slice(-5)}</strong>
                <span>{open ? '예매 가능' : status}</span>
                {schedule.salesStatus === 'BEFORE_OPEN' && !closed && (
                  <small>{formatDateTime(schedule.bookingOpenAt)} 오픈</small>
                )}
              </button>
            );
          })}
        </div>
        {schedules.length === 0 && <p className="booking-hint">등록된 회차가 없어요.</p>}
        {schedules.length > 0 && !schedules.some((schedule) => canBook(schedule, now)) && (
          <p className="booking-hint">
            현재 예매 가능한 회차가 없어요. 오픈 일정과 판매 상태를 확인해 주세요.
          </p>
        )}
      <button type="button" className="booking-refresh" onClick={() => { refresh(); if (selected) remaining.refetch(); }}>
          판매 상태 새로고침 ↻
        </button>
      </section>
      <section aria-labelledby="grade-heading">
        <h3 id="grade-heading">좌석등급·가격 {selected && <span>잔여석</span>}</h3>
        {selected && remaining.isPending && <Loading />}
        {selected && remaining.isError && (
          <div>
            <ErrorNotice error={remaining.error} />
            <button type="button" className="booking-refresh" onClick={() => remaining.refetch()}>
              잔여석 다시 확인 ↻
            </button>
          </div>
        )}
        <dl className="booking-prices">
          {item.gradePrices.map((grade) => {
            const rest = remaining.data?.grades.find(
              (value) => value.venueSeatGradeId === grade.venueSeatGradeId,
            );
            return (
              <div key={grade.venueSeatGradeId}>
                <dt>
                  {grade.gradeName}
                  {selected && rest && (
                    <span className={rest.remaining === 0 ? 'sold-out' : ''}>
                      {rest.remaining === 0 ? '매진' : `${rest.remaining}석`}
                    </span>
                  )}
                </dt>
                <dd>{won(grade.price)}</dd>
              </div>
            );
          })}
        </dl>
        {item.gradePrices.length === 0 && <p className="booking-hint">등록된 가격 정보가 없어요.</p>}
        <p className="calendar-help">좌석은 다음 단계에서 선택해요. 잔여석은 예매 중 달라질 수 있어요.</p>
      </section>
      <div className="booking-action">
        {selected && member && !soldOut && !busyAccount ? (
          <Link className="btn btn-primary btn-block" to={next}>
            예매하기 <span aria-hidden="true">→</span>
          </Link>
        ) : (
          <button type="button" className="btn btn-primary btn-block" disabled>
            {busyAccount ? '회원 정보를 확인해 주세요' : buttonLabel}
          </button>
        )}
        {selected && (
          <p className="booking-selected" aria-live="polite">
            {formatDateTime(selected.startAt)} 관람
          </p>
        )}
      </div>
    </aside>
  );
}
