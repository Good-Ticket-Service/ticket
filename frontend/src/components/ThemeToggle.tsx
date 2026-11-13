import { useState } from 'react';

export function ThemeToggle() {
  const [light, setLight] = useState(() => document.documentElement.dataset.theme === 'light');
  return (
    <button
      type="button"
      className="theme-toggle"
      role="switch"
      aria-checked={light}
      aria-label="라이트 모드"
      onClick={() => {
        const next = !light;
        setLight(next);
        document.documentElement.dataset.theme = next ? 'light' : 'dark';
        try {
          localStorage.setItem('safe-ticket-theme', next ? 'light' : 'dark');
        } catch {
          /* 저장이 차단돼도 현재 테마는 바꾼다. */
        }
      }}
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
        {light ? (
          <>
            <circle cx="12" cy="12" r="4" />
            <path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5" />
          </>
        ) : (
          <path d="M20.5 14.5A9 9 0 0 1 9.5 3.5a9 9 0 1 0 11 11Z" />
        )}
      </svg>
      <span className="theme-track">
        <span />
      </span>
    </button>
  );
}
