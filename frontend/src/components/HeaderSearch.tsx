import { useEffect, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';

export function HeaderSearch() {
  const [params] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const keyword = params.get('keyword') ?? '';
  const [value, setValue] = useState(keyword);
  useEffect(() => setValue(keyword), [keyword, location.pathname]);
  const search = (text: string, replace = false) => {
    const next = location.pathname === '/' ? new URLSearchParams(params) : new URLSearchParams();
    if (text) next.set('keyword', text);
    else next.delete('keyword');
    next.delete('page');
    navigate(
      {
        pathname: '/',
        search: next.toString(),
        hash: replace ? '' : '#performances',
      },
      { replace, preventScrollReset: true },
    );
  };
  return (
    <form
      className="header-search"
      role="search"
      onSubmit={(event) => {
        event.preventDefault();
        search(value.trim());
      }}
    >
      <label className="sr-only" htmlFor="header-search">
        공연명 검색
      </label>
      <input
        id="header-search"
        type="search"
        placeholder="어떤 공연을 찾고 있나요?"
        value={value}
        onChange={(event) => {
          setValue(event.target.value);
          if (location.pathname === '/') search(event.target.value, true);
        }}
      />
      <button type="submit" aria-label="공연 검색">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true">
          <circle cx="10.5" cy="10.5" r="6.5" />
          <path d="m16 16 5 5" />
        </svg>
      </button>
    </form>
  );
}
