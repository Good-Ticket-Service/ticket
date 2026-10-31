export interface PageP<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
}

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
  region: string;
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
  ageRating: string;
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

export interface Hold {
  reservationId: number;
  scheduleSeatId: number;
  status: 'HOLD';
  holdExpiresAt: string;
  basePrice: number;
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

export interface CouponDetail {
  couponId: number;
  name: string;
  discountAmount: number;
  remainingQuantity: number;
  issueStartAt: string;
  issueEndAt: string;
  validDays: number;
  issueStatus: string;
  issuedByMe: boolean;
}

export interface UserCoupon {
  userCouponId: number;
  couponId: number;
  name: string;
  discountAmount: number;
  status: string;
  issuedAt: string;
  expiresAt: string;
  usedAt: string | null;
}

export interface QueuePosition {
  status?: string;
  position: number;
  aheadCount: number;
  estimatedWaitSeconds: number;
}

export type PgProvider = 'TOSS' | 'KAKAO';
