import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, NavLink, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { api, getToken, setToken } from '../api/client';
import type { Role, User } from '../api/types';
import { ErrorNotice, Loading } from './Common';

function useToken(): string | null {
  const [token, setCurrent] = useState(getToken);
  useEffect(() => {
    const update = () => setCurrent(getToken());
    window.addEventListener('ticket-auth-changed', update);
    return () => window.removeEventListener('ticket-auth-changed', update);
  }, []);
  return token;
}

export function useLoggedIn(): boolean {
  return useToken() !== null;
}

/**
 * 로그인한 회원. 로그인 응답에 역할이 없어 본인 조회로 읽는다.
 * 토큰이 바뀌면 다른 회원일 수 있으므로 토큰마다 따로 읽는다.
 */
export function useMe() {
  const token = useToken();
  return useQuery({
    queryKey: ['me', token],
    enabled: token !== null,
    queryFn: () => api<User>('/api/users/me'),
    staleTime: Infinity,
  });
}

export const ROLE_LABEL: Record<Role, string> = { USER: '회원', SELLER: '판매자', MASTER: '관리자' };

// 예매·쿠폰 발급은 USER만 할 수 있어 다른 역할에는 메뉴를 보이지 않는다. 관리 메뉴는 MASTER에만 보인다.
const links: { to: string; label: string; end: boolean; roles: Role[] }[] = [
  { to: '/', label: '공연', end: true, roles: ['USER', 'SELLER', 'MASTER'] },
  { to: '/coupons', label: '쿠폰함', end: false, roles: ['USER'] },
  { to: '/reservations', label: '내 예매', end: false, roles: ['USER'] },
  { to: '/admin/coupons', label: '쿠폰 관리', end: false, roles: ['MASTER'] },
];

function navClass({ isActive }: { isActive: boolean }) {
  return isActive ? 'navlink active' : 'navlink';
}

export function Layout() {
  const loggedIn = useLoggedIn();
  const me = useMe();
  const navigate = useNavigate();
  // 역할을 읽기 전과 로그인 전에는 회원 메뉴를 보인다. 로그인이 필요한 메뉴는 누르면 로그인으로 보낸다.
  const role = me.data?.role ?? 'USER';
  const visible = links.filter((link) => link.roles.includes(role));
  return (
    <div className="app">
      <header className="topbar">
        <Link to="/" className="brand">
          Ticket
        </Link>
        <nav aria-label="주요 메뉴" className="topnav">
          {visible.map((link) => (
            <NavLink key={link.to} to={link.to} end={link.end} className={navClass}>
              {link.label}
            </NavLink>
          ))}
        </nav>
        {loggedIn ? (
          <span className="account">
            {me.data && (
              <span className="muted">
                {me.data.name} · {ROLE_LABEL[me.data.role]}
              </span>
            )}
            <button
              type="button"
              className="navlink"
              onClick={() => {
                setToken(null);
                navigate('/');
              }}
            >
              로그아웃
            </button>
          </span>
        ) : (
          <Link to="/login" className="navlink">
            로그인
          </Link>
        )}
      </header>
      <Outlet />
      <nav aria-label="주요 메뉴" className="bottomnav">
        {visible.map((link) => (
          <NavLink key={link.to} to={link.to} end={link.end} className={navClass}>
            {link.label}
          </NavLink>
        ))}
      </nav>
    </div>
  );
}

export function RequireLogin() {
  const loggedIn = useLoggedIn();
  const location = useLocation();
  if (!loggedIn) {
    return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />;
  }
  return <Outlet />;
}

/** 역할이 맞지 않으면 화면 대신 안내를 보인다. 서버도 같은 역할을 검사하므로 이것은 길 안내일 뿐이다. */
export function RequireRole({ roles }: { roles: Role[] }) {
  const me = useMe();
  if (me.isPending) return <main className="content"><Loading /></main>;
  if (me.isError) return <main className="content"><ErrorNotice error={me.error} /></main>;
  if (!roles.includes(me.data.role)) {
    return (
      <main className="content narrow">
        <p className="notice">{roles.map((role) => ROLE_LABEL[role]).join('·')} 계정으로 이용할 수 있어요.</p>
      </main>
    );
  }
  return <Outlet />;
}
