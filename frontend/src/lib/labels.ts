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
