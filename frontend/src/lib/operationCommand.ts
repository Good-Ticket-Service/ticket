/** 응답 불명인 운영 명령은 ID·경로·입력을 함께 고정한다. */
export function commandSession() {
  let saved: { path: string; body: Record<string, unknown> } | null = null;
  return {
    prepare(path: string, body: Record<string, unknown>) {
      if (!saved) saved = { path, body: { ...body } };
      return saved;
    },
    settle(status?: number) {
      if (status === undefined || (status !== 0 && status < 500)) saved = null;
    },
  };
}

export function operationMinutes(value: string) {
  if (!value) return '';
  const n = Number(value);
  if (!/^\d+$/.test(value) || !Number.isSafeInteger(n) || n > 5256000)
    throw new Error('경과 시간은 0~5,256,000분 사이 정수로 입력해 주세요.');
  return String(n);
}
