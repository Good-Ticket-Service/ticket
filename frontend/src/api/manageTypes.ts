/** 판매자·관리자 화면의 요청·응답. */

import type { Genre } from '../lib/genres';
export type { Genre } from '../lib/genres';
export type AgeRating = 'ALL' | '12' | '15' | '19';

/** 공연장 등록 응답 */
export interface VenueResult {
  id: number;
  name: string;
  address: string;
  region: string;
  version: number;
}

/** 등급 등록 응답. 이번에 만든 등급만 온다. */
export interface GradesResult {
  grades: { id: number; name: string }[];
  venueVersion: number;
}

/** 좌석 등록 응답 */
export interface SeatsResult {
  createdCount: number;
  createdSeatIds: number[];
  venueVersion: number;
}

export interface SeatInput {
  section: string;
  rowLabel: string;
  seatNumber: number;
  venueSeatGradeId: number;
}

export interface PerformanceInput {
  venueId: number;
  title: string;
  genre: Genre;
  ageRating?: AgeRating | null;
  ageGuidance?: string | null;
  runningTime: number;
  castInfo: string | null;
  description: string | null;
  posterUrl: string | null;
}

/** 판매자 공연(초안 등록·공개 응답) */
export interface SellerPerformance {
  id: number;
  venue: { id: number; name: string; address: string; region: string };
  title: string;
  genre: string;
  ageRating: AgeRating | null;
  ageGuidance: string | null;
  publicationStatus: string;
  operationStatus: string;
  publishedAt: string | null;
  version: number;
}

/** 등급별 가격 설정 응답 */
export interface GradePricesResult {
  gradePrices: { venueSeatGradeId: number; gradeName: string; price: number }[];
  performanceVersion: number;
}

/** 판매자 회차(등록·조회 응답) */
export interface SellerSchedule {
  id: number;
  performanceId: number;
  startAt: string;
  bookingOpenAt: string;
  bookingCloseAt: string;
  operationStatus: string;
  seatSetupStatus: string;
  salesSuspended: boolean;
  salesStatus: string;
  canceledAt: string | null;
  seatConfigVersion: number;
  seatSetupRequestId: string;
  seatSetupError: string | null;
  suspensionReason: string | null;
  cancelReason: string | null;
  version: number;
}

/** 판매 일시 중지 응답 */
export interface SalesSuspensionResult {
  scheduleId: number;
  salesSuspended: boolean;
  suspensionReason: string | null;
  version: number;
}

/** 공개 회차. version이 없다. */
export interface PublicSchedule {
  id: number;
  performanceId: number;
  startAt: string;
  bookingOpenAt: string;
  bookingCloseAt: string;
  operationStatus: string;
  seatSetupStatus: string;
  salesSuspended: boolean;
  salesStatus: string;
  canceledAt: string | null;
}

/** 공개 좌석 배치. 공개된 공연만 조회된다. */
export interface SeatLayout {
  performanceId: number;
  venue: { id: number; name: string };
  seats: {
    id: number;
    section: string;
    rowLabel: string;
    seatNumber: number;
    venueSeatGradeId: number;
    gradeName: string;
    isSellable: boolean;
    basePrice: number | null;
  }[];
}

export type CouponIssueStatus = 'UPCOMING' | 'ISSUING' | 'ENDED';

export interface CouponInput {
  name: string;
  discountAmount: number;
  totalQuantity: number;
  issueStartAt: string;
  issueEndAt: string;
  validDays: number;
}

export interface Coupon extends CouponInput {
  couponId: number;
  remainingQuantity: number;
  issueStatus: CouponIssueStatus;
}

export type RefundStatus = 'WAITING' | 'REQUESTED' | 'DONE' | 'FAILED' | 'SKIPPED';
export type RefundRecoveryMode = 'ACTIVE' | 'SLOW' | 'VERIFY_ONLY' | 'PAUSED';
export type RefundOperationReason = 'RESUME_OBSERVATION' | 'ROOT_CAUSE_FIXED' | 'AUTH_CONFIGURATION_FIXED';

export interface CursorPage<T> {
  items: T[];
  nextCursor: number | null;
}

export interface RefundOperationView {
  refundId: number;
  orderId: string;
  paymentStatus: string;
  status: RefundStatus;
  mode: RefundRecoveryMode;
  provider: string;
  refundReason: string;
  amount: number;
  executionVersion: number;
  cancelAttempt: number;
  automaticallyQueryable: boolean;
  automaticCancelAllowed: boolean;
  guidance: string;
  requestedAt: string | null;
  nextAttemptAt: string | null;
  restrictionReason: string | null;
  lastError: string | null;
  failureCode: string | null;
}

export interface RefundDetail {
  current: RefundOperationView;
  commands: {
    commandId: string;
    action: string;
    actor: string;
    reason: string;
    result: string;
    explanation: string;
    processedAt: string;
  }[];
  pgObservation: string;
  observedAt: string | null;
}

export interface RefundOperationOutcome {
  commandId: string;
  result: string;
  explanation: string;
  status: RefundStatus;
  mode: RefundRecoveryMode;
  executionVersion: number;
  cancelAttempt: number;
  processedAt: string;
}

export type PayoutStatus = 'READY' | 'REQUESTED' | 'DONE' | 'FAILED';
export type PayoutAttention =
  | 'ACCOUNT_MISSING'
  | 'GATE_BLOCKED'
  | 'READY_UNSCHEDULED'
  | 'READY'
  | 'RESULT_UNKNOWN'
  | 'INQUIRY_ONLY'
  | 'IN_PROGRESS'
  | 'FAILED'
  | 'DONE';

export interface PayoutView {
  payoutId: number;
  statementId: number;
  sellerId: number;
  amount: number;
  status: PayoutStatus;
  attention: PayoutAttention;
  attempt: number;
  version: number;
  providerStatus: string | null;
  inquiryOnly: boolean;
  verificationReason: string | null;
  failureCode: string | null;
  lastError: string | null;
  nextAttemptAt: string | null;
  requestedAt: string | null;
  doneAt: string | null;
  bankCode: string | null;
  holderMasked: string | null;
}

export interface PayoutDetail {
  current: PayoutView;
  commands: {
    commandId: string;
    action: string;
    actor: string;
    reason: string;
    note: string;
    result: string;
    explanation: string;
    processedAt: string;
  }[];
}

export interface PayoutRetryOutcome {
  commandId: string;
  result: string;
  explanation: string;
  status: PayoutStatus;
  attempt: number;
  version: number;
  processedAt: string;
}

export interface SellerReceivable {
  sellerId: number;
  receivable: number;
  openCount: number;
  oldestAt: string;
}

export interface PayoutAccount {
  bankCode: string;
  accountMasked: string;
  holderMasked: string;
  version: number;
  updatedAt: string;
}

export type StatementStatus = 'ISSUED' | 'NO_PAYOUT' | 'PAID';

export interface StatementSummary {
  statementId: number;
  scheduleId: number;
  scheduleStartAt: string;
  sequence: number;
  saleTotal: number;
  offsetTotal: number;
  payableAmount: number;
  status: StatementStatus;
  issuedAt: string;
  paidAt: string | null;
  payoutStatus: string | null;
}

export interface StatementDetail extends StatementSummary {
  lines: {
    itemId: number;
    kind: 'SALE' | 'DEDUCTION';
    paymentId: number;
    grossAmount: number | null;
    commission: number | null;
    commissionVat: number | null;
    sellerAmount: number | null;
    amount: number;
  }[];
}
