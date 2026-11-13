import type { Schedule } from '../api/types';
import { parseTime } from './format';

const seoulDate = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul' });

export function calendarDate(value: string | number): string {
  return seoulDate.format(typeof value === 'number' ? new Date(value) : parseTime(value));
}

export function canBook(schedule: Schedule, now: number): boolean {
  return (
    schedule.salesStatus === 'ON_SALE' &&
    !schedule.salesSuspended &&
    !schedule.canceledAt &&
    parseTime(schedule.bookingOpenAt).getTime() <= now &&
    now < parseTime(schedule.bookingCloseAt).getTime()
  );
}

export function orderedSchedules(schedules: Schedule[]): Schedule[] {
  return [...schedules].sort(
    (a, b) => parseTime(a.startAt).getTime() - parseTime(b.startAt).getTime() || a.id - b.id,
  );
}

export function initialCalendarSelection(schedules: Schedule[], now: number) {
  const ordered = orderedSchedules(schedules);
  const onSale = ordered.find((schedule) => canBook(schedule, now));
  const future = ordered.find(
    (schedule) =>
      parseTime(schedule.startAt).getTime() > now &&
      !schedule.canceledAt &&
      schedule.salesStatus !== 'CANCELED',
  );
  const date = onSale || future ? calendarDate((onSale ?? future)!.startAt) : '';
  return { month: (date || calendarDate(now)).slice(0, 7), date, scheduleId: onSale?.id ?? null };
}

export function shiftCalendarMonth(month: string, delta: number): string {
  const [year, value] = month.split('-').map(Number);
  return new Date(Date.UTC(year, value - 1 + delta, 1)).toISOString().slice(0, 7);
}

export function monthCells(month: string): (string | null)[] {
  const [year, value] = month.split('-').map(Number);
  const first = new Date(Date.UTC(year, value - 1, 1));
  const count = new Date(Date.UTC(year, value, 0)).getUTCDate();
  const cells: (string | null)[] = Array(first.getUTCDay()).fill(null);
  for (let day = 1; day <= count; day++) cells.push(`${month}-${String(day).padStart(2, '0')}`);
  while (cells.length % 7 !== 0) cells.push(null);
  return cells;
}

export function schedulesByDate(schedules: Schedule[]): Map<string, Schedule[]> {
  const days = new Map<string, Schedule[]>();
  for (const schedule of orderedSchedules(schedules)) {
    const date = calendarDate(schedule.startAt);
    const bucket = days.get(date);
    if (bucket) bucket.push(schedule);
    else days.set(date, [schedule]);
  }
  return days;
}
