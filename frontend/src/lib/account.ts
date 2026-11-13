import type { User } from '../api/types';

export const PHONE_PATTERN = '01[016789]-\\d{3,4}-\\d{4}';
export const PASSWORD_PATTERN = '(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,15}';

/** 로그인 복귀는 브라우저가 외부 URL로 해석할 여지가 없는 앱 경로만 허용한다. */
export function loginDestination(next: string | null): string {
  return next?.startsWith('/') && !next.startsWith('//') && !/[\\\s\u0000-\u001f]/.test(next) ? next : '/';
}

export function profileChanges(
  current: Pick<User, 'name' | 'phone'>,
  values: { name: string; phone: string },
) {
  const changes: { name?: string; phone?: string } = {};
  if (values.name !== current.name) changes.name = values.name;
  if (values.phone !== current.phone) changes.phone = values.phone;
  return Object.keys(changes).length ? changes : null;
}
