import type { Reservation, ReservationStatus } from '../api/types';
import { parseTime } from './format';

export const BOOKING_FILTERS: { value: ReservationStatus | ''; label: string }[] = [
  { value: '', label: '전체' },
  { value: 'CONFIRMED', label: '예매 완료' },
  { value: 'HOLD', label: '결제 대기' },
  { value: 'PAYMENT_IN_PROGRESS', label: '결제 확인 중' },
  { value: 'CANCELING', label: '취소 처리 중' },
  { value: 'CANCELED', label: '예매 취소' },
  { value: 'EXPIRED', label: '시간 만료' },
  { value: 'RELEASED', label: '선점 해제' },
];
export const BOOKING_COPY: Record<
  ReservationStatus,
  { label: string; title: string; body: string; tone: string }
> = {
  HOLD: {
    label: '결제 대기',
    title: '결제를 기다리고 있어요',
    body: '좌석 선점이 유지되는 동안 결제를 이어갈 수 있어요. 결제 가능한 남은 시간을 확인해 주세요.',
    tone: 'waiting',
  },
  PAYMENT_IN_PROGRESS: {
    label: '결제 확인 중',
    title: '결제 결과를 확인하고 있어요',
    body: '결과가 확인되면 예매 상태가 갱신돼요. 이 창을 닫아도 처리는 계속되며, 중복 결제 없이 현재 예매에서 결과를 확인할 수 있어요.',
    tone: 'waiting',
  },
  CONFIRMED: {
    label: '예매 완료',
    title: '예매가 완료됐어요',
    body: '공연 일정과 선택한 좌석, 발급된 티켓 코드를 확인해 주세요.',
    tone: 'confirmed',
  },
  CANCELING: {
    label: '취소 처리 중',
    title: '취소 요청을 처리하고 있어요',
    body: '취소 요청을 받았어요. 처리 결과가 확인되면 자동으로 갱신돼요. 이 창을 닫아도 취소는 계속 진행돼요.',
    tone: 'waiting',
  },
  CANCELED: {
    label: '예매 취소',
    title: '예매가 취소됐어요',
    body: '환불이 필요한 결제는 접수 후 PG 처리와 결제 수단 반영까지 시간이 걸릴 수 있어요.',
    tone: 'closed',
  },
  EXPIRED: {
    label: '시간 만료',
    title: '좌석 선점 시간이 끝났어요',
    body: '다시 예매하려면 좌석을 선택해 주세요. 반환된 쿠폰은 원래 유효기간에 따라 쿠폰함에 표시돼요.',
    tone: 'closed',
  },
  RELEASED: {
    label: '선점 해제',
    title: '좌석 선점을 해제했어요',
    body: '다시 예매하려면 좌석을 선택해 주세요. 반환된 쿠폰은 쿠폰함에서 확인할 수 있어요.',
    tone: 'closed',
  },
};

export const bookingPending = (status: ReservationStatus) =>
  status === 'PAYMENT_IN_PROGRESS' || status === 'CANCELING';
export function bookingPollInterval(
  status: ReservationStatus,
  retryAfter: string | null,
  now: number,
): number | false {
  if (status === 'HOLD') return 5000;
  if (!bookingPending(status)) return false;
  const seconds = retryAfter === null ? NaN : Number(retryAfter);
  const delay = Number.isFinite(seconds) ? seconds * 1000 : retryAfter ? Date.parse(retryAfter) - now : NaN;
  return Number.isFinite(delay) && delay >= 0 ? Math.max(1000, delay) : 3000;
}
export const canCancelBooking = (status: ReservationStatus, startAt: string, now: number) =>
  status === 'CONFIRMED' && parseTime(startAt).getTime() > now;
export const canResumeBooking = (status: ReservationStatus, until: string | undefined, now: number) =>
  status === 'HOLD' && !!until && parseTime(until).getTime() > now;
export function bookingTicketCodes(item: Pick<Reservation, 'status' | 'tickets'>) {
  return item.status === 'CONFIRMED' ? item.tickets.filter((ticket) => !!ticket.ticketCode) : [];
}
