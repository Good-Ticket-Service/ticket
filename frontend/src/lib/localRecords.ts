import { useCallback, useState } from 'react';

/**
 * 판매자 공연·공연장 목록 API가 없어, 이 브라우저에서 만든 것을 기억해 두고 이어서 작업한다.
 * 다른 기기나 다른 브라우저에서 만든 것은 ID로 직접 찾는다.
 */
function read<T>(key: string): T[] {
  try {
    return JSON.parse(localStorage.getItem(key) ?? '[]') as T[];
  } catch {
    return [];
  }
}

export function useLocalRecords<T extends { id: number }>(key: string) {
  const [records, setRecords] = useState<T[]>(() => read<T>(key));
  const save = useCallback(
    (record: T) => {
      const next = [record, ...read<T>(key).filter((item) => item.id !== record.id)];
      try {
        localStorage.setItem(key, JSON.stringify(next));
      } catch {
        // 저장하지 못하면 이번 화면에서만 기억한다.
      }
      setRecords(next);
    },
    [key],
  );
  const remove = useCallback(
    (id: number) => {
      const next = read<T>(key).filter((item) => item.id !== id);
      try {
        localStorage.setItem(key, JSON.stringify(next));
      } catch {
        // 지우지 못하면 다음에 다시 보인다.
      }
      setRecords(next);
    },
    [key],
  );
  return { records, find: (id: number) => records.find((item) => item.id === id), save, remove };
}

/** 운영 명령의 멱등 키. 결과를 모를 때 같은 키로 다시 보내 최초 결과를 받는다. */
export function newCommandId(): string {
  return `op_${crypto.randomUUID().replace(/-/g, '')}`;
}
