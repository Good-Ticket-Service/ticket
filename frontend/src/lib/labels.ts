import type { SalesStatus } from '../api/types';

/** 회차 상태 코드를 화면 문구로 바꾼다. 모르는 값은 코드를 그대로 보인다. */
export const SALES_STATUS_LABEL: Record<SalesStatus, string> = {
  ON_SALE: '예매 가능',
  BEFORE_OPEN: '오픈 전',
  UNAVAILABLE: '판매 중지',
  CLOSED: '예매 종료',
  CANCELED: '회차 취소',
};

const SEAT_SETUP_LABEL: Record<string, string> = {
  PENDING: '준비 중',
  READY: '준비 완료',
  FAILED: '준비 실패',
};

const OPERATION_LABEL: Record<string, string> = {
  ACTIVE: '운영 중',
  CANCELED: '취소됨',
};

export function salesStatusLabel(code: string): string {
  return SALES_STATUS_LABEL[code as SalesStatus] ?? code;
}

export function seatSetupLabel(code: string): string {
  return SEAT_SETUP_LABEL[code] ?? code;
}

export function operationLabel(code: string): string {
  return OPERATION_LABEL[code] ?? code;
}

export function ageRatingLabel(code: string): string {
  if (code === 'ALL') return '전체 관람가';
  return /^\d+$/.test(code) ? `${code}세 이상` : code;
}

/** 운영 화면은 로그·알림과 맞춰 보도록 코드를 남기고 뜻을 앞에 붙인다. */
function withCode(labels: Record<string, string>, code: string): string {
  return labels[code] ? `${labels[code]} · ${code}` : code;
}

const REFUND_STATUS: Record<string, string> = {
  WAITING: '대기',
  REQUESTED: 'PG에 요청함',
  DONE: '완료',
  FAILED: '실패',
  SKIPPED: '건너뜀',
};

const RECOVERY_MODE: Record<string, string> = {
  ACTIVE: '자동 처리',
  SLOW: '느리게 재시도',
  VERIFY_ONLY: '조회만',
  PAUSED: '멈춤, 운영 확인 필요',
};

const PG_OBSERVATION: Record<string, string> = {
  NOT_QUERIED: '조회 안 함',
  APPROVED: '승인 상태',
  CANCELED_REQUIRES_VALIDATION: '취소됨, 확인 필요',
  INCONSISTENT: '불일치',
  NOT_FOUND: 'PG에 없음',
  UNKNOWN: '모름',
};

const PAYOUT_STATUS: Record<string, string> = {
  READY: '지급 대기',
  REQUESTED: '지급 요청함',
  DONE: '지급 완료',
  FAILED: '지급 실패',
};

const PAYOUT_ATTENTION: Record<string, string> = {
  ACCOUNT_MISSING: '계좌 미등록',
  GATE_BLOCKED: '지급 전 확인 실패',
  READY_UNSCHEDULED: '영업일 달력 밖',
  READY: '대기',
  RESULT_UNKNOWN: '결과 모름',
  INQUIRY_ONLY: '조회만',
  IN_PROGRESS: '처리 중',
  FAILED: '실패',
  DONE: '완료',
};

export const refundStatusLabel = (code: string) => withCode(REFUND_STATUS, code);
export const recoveryModeLabel = (code: string) => withCode(RECOVERY_MODE, code);
export const pgObservationLabel = (code: string) => withCode(PG_OBSERVATION, code);
export const payoutStatusLabel = (code: string) => withCode(PAYOUT_STATUS, code);
export const payoutAttentionLabel = (code: string) => withCode(PAYOUT_ATTENTION, code);
