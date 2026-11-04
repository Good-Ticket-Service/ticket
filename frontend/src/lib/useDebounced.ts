import { useEffect, useState } from 'react';

/** 값이 delay 동안 바뀌지 않으면 그 값을 돌려준다. 입력할 때마다 요청하지 않으려고 쓴다. */
export function useDebounced<T>(value: T, delay = 300): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setSettled(value), delay);
    return () => window.clearTimeout(timer);
  }, [value, delay]);
  return settled;
}
