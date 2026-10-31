const TOKEN_KEY = 'ticket.accessToken';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token: string | null): void {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    // 저장소를 쓸 수 없는 브라우저에서는 이번 화면에서만 로그인이 유지되지 않는다.
  }
  window.dispatchEvent(new Event('ticket-auth-changed'));
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
}

async function send(path: string, options: RequestOptions): Promise<Response> {
  const token = getToken();
  try {
    return await fetch(path, {
      method: options.method ?? 'GET',
      headers: {
        ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...options.headers,
      },
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', '서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.');
  }
}

async function toError(response: Response): Promise<ApiError> {
  const body = await response.json().catch(() => null);
  if (response.status === 401 && getToken()) setToken(null);
  return new ApiError(
    response.status,
    body?.code ?? `HTTP_${response.status}`,
    body?.message ?? '요청을 처리하지 못했어요.',
  );
}

/** 외부 API 호출. 성공 응답의 ApiResponse.data를 풀어 돌려준다. */
export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await send(path, options);
  if (!response.ok) throw await toError(response);
  if (response.status === 204) return undefined as T;
  const body = await response.json();
  return body.data as T;
}

/** Fake PG처럼 ApiResponse로 감싸지 않은 응답을 돌려주는 호출. */
export async function rawApi<T>(path: string, body: unknown): Promise<T> {
  const response = await send(path, { method: 'POST', body });
  if (!response.ok) throw await toError(response);
  return (await response.json()) as T;
}

export function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return '문제가 생겼어요. 잠시 뒤 다시 시도해 주세요.';
  return MESSAGES[error.code] ?? error.message;
}

const MESSAGES: Record<string, string> = {
  HOLD_EXPIRED: '좌석을 잡아 둔 시간이 끝났어요. 좌석을 다시 골라 주세요.',
  SEAT_ALREADY_TAKEN: '방금 다른 분이 고른 좌석이에요. 다른 좌석을 골라 주세요.',
  PURCHASE_LIMIT_EXCEEDED: '이 회차에 진행 중인 예매가 이미 있어요. 내 예매에서 확인해 주세요.',
  COUPON_NOT_AVAILABLE: '이 쿠폰은 쓸 수 없어요. 다른 쿠폰을 골라 주세요.',
  CHECKOUT_CONFLICT: '다른 창에서 결제를 처리했을 수 있어요. 상태를 다시 확인해 주세요.',
  ORDER_ID_MISMATCH: '다른 창에서 결제를 다시 준비했어요. 상태를 다시 확인해 주세요.',
  NOT_CANCELLABLE: '회차가 시작돼 취소할 수 없어요.',
  COUPON_SOLD_OUT: '쿠폰이 모두 소진됐어요.',
  COUPON_ALREADY_ISSUED: '이미 받은 쿠폰이에요.',
  COUPON_ISSUE_PERIOD_CLOSED: '쿠폰 발급 기간이 아니에요.',
  LOGIN_FAILED: '이메일이나 비밀번호가 맞지 않아요.',
  DUPLICATE_EMAIL: '이미 가입한 이메일이에요.',
  QUEUE_TOKEN_INVALID: '입장 시간이 끝났어요. 다시 줄을 서 주세요.',
  SERVICE_UNAVAILABLE: '요청이 몰리고 있어요. 잠시 뒤 다시 시도해 주세요.',
};
