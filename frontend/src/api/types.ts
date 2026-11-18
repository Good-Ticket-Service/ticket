import type { Region } from '../lib/regions';

export interface PageC<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  sort: string | null;
}

export interface Venue {
  id: number;
  name: string;
  address: string;
  /** 지역 코드. 화면에는 regionLabel로 표시한다. */
  region: Region;
}

export interface Period {
  from: string;
  to: string;
}

export interface PerformanceSummary {
  id: number;
  venue: Venue;
  title: string;
  genre: string;
  posterUrl: string | null;
  operationStatus: string;
  period: Period | null;
}

export interface GradePrice {
  venueSeatGradeId: number;
  gradeName: string;
  price: number;
}

export interface PerformanceDetail extends PerformanceSummary {
  ageRating: string | null;
  ageGuidance: string | null;
  runningTime: number;
  castInfo: string | null;
  description: string | null;
  gradePrices: GradePrice[];
}

export type SalesStatus = 'CANCELED' | 'CLOSED' | 'BEFORE_OPEN' | 'UNAVAILABLE' | 'ON_SALE';

export interface Schedule {
  id: number;
  performanceId: number;
  startAt: string;
  bookingOpenAt: string;
  bookingCloseAt: string;
  salesStatus: SalesStatus;
  salesSuspended: boolean;
  canceledAt: string | null;
}

export interface RemainingGrade {
  venueSeatGradeId: number;
  gradeName: string;
  remaining: number;
}

export interface Seat {
  scheduleSeatId: number;
  venueSeatId: number;
  section: string;
  rowLabel: string;
  seatNumber: number;
  venueSeatGradeId: number;
  gradeName: string;
  basePrice: number | null;
  selectable: boolean;
}

/** 선점·좌석 선택 완료 응답. REDIS 모드 선점은 holdId가 있고 reservationId가 null이다(DB 모드·좌석 선택 완료는 반대). */
export interface Hold {
  reservationId: number | null;
  scheduleSeatId: number;
  status: 'HOLD';
  holdExpiresAt: string;
  basePrice: number;
  holdId: string | null;
}

/** REDIS 모드 선점 조회. reservationId는 CONVERTED일 때만 있다. */
export interface HoldView {
  holdId: string;
  scheduleId: number;
  scheduleSeatId: number;
  status: 'HELD' | 'CONVERTING' | 'CONVERTED';
  reservationId: number | null;
  holdExpiresAt: string;
  basePrice: number;
  performanceId: number;
  performanceTitle: string;
  genre: string;
  scheduleStartAt: string;
  gradeName: string;
}

export interface WaitingRoom {
  roomId: number;
  scopes: string[];
  openAt: string;
  closeAt: string;
}

export interface SeatLayout {
  scheduleId: number;
  configVersion: number;
  seats: Omit<Seat, 'selectable'>[];
}

export interface SeatStatus {
  scheduleId: number;
  configVersion: number;
  asOf: string;
  seatCount: number;
  selectableBitmap: string;
}

export type ReservationStatus =
  | 'HOLD'
  | 'PAYMENT_IN_PROGRESS'
  | 'CONFIRMED'
  | 'CANCELING'
  | 'EXPIRED'
  | 'RELEASED'
  | 'CANCELED';

export interface Ticket {
  scheduleSeatId: number;
  price: number;
  ticketCode: string | null;
}

export interface Reservation {
  reservationId: number;
  scheduleId: number;
  performanceId: number;
  performanceTitle: string;
  scheduleStartAt: string;
  scheduleSeatIds: number[];
  status: ReservationStatus;
  holdExpiresAt: string;
  orderId: string | null;
  totalPrice: number;
  userCouponId: number | null;
  discountAmount: number | null;
  paymentAmount: number | null;
  tickets: Ticket[];
}

export interface ReservationSummary {
  reservationId: number;
  scheduleId: number;
  performanceId: number;
  performanceTitle: string;
  scheduleStartAt: string;
  status: ReservationStatus;
  paymentAmount: number | null;
  seatCount: number;
  createdAt: string;
}

export interface Checkout {
  orderId: string;
  amount: number;
  checkoutUrl: string | null;
}

export interface CouponPolicy {
  discountType: 'FIXED' | 'RATE';
  discountAmount: number | null;
  discountRate: number | null;
  maxDiscountAmount: number | null;
  minBaseAmount: number | null;
}

export interface CouponDetail extends CouponPolicy {
  totalQuantity: number;
  couponId: number;
  name: string;
  category: 'GENERAL' | 'WELCOME';
  genre: string | null;
  remainingQuantity: number;
  issueStartAt: string;
  issueEndAt: string;
  validDays: number;
  issueStatus: string;
  issuedByMe: boolean;
}

export interface UserCoupon extends CouponPolicy {
  userCouponId: number;
  couponId: number;
  name: string | null;
  genre: string | null;
  status: string;
  issuedAt: string;
  expiresAt: string;
  usedAt: string | null;
}

/** ENTERED면 position·aheadCount·estimatedWaitSeconds가 0이고, WAITING이면 entryToken·entryExpiresAt이 null이다. */
export interface QueueStatus {
  status: 'WAITING' | 'ENTERED';
  position: number;
  aheadCount: number;
  estimatedWaitSeconds: number;
  entryToken: string | null;
  entryExpiresAt: string | null;
}

export type Role = 'USER' | 'SELLER' | 'MASTER';

export interface User {
  userId: number;
  email: string;
  name: string;
  phone: string;
  role: Role;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export type PgProvider = 'TOSS' | 'KAKAO';
