import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
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

// 예매·쿠폰 발급은 USER만 할 수 있어 다른 역할에는 메뉴를 보이지 않는다. 판매·관리 메뉴는 그 역할에만 보인다.
const links: { to: string; label: string; end: boolean; roles: Role[] }[] = [
  { to: '/', label: '공연', end: true, roles: ['USER', 'SELLER', 'MASTER'] },
  { to: '/coupons', label: '쿠폰함', end: false, roles: ['USER'] },
  { to: '/reservations', label: '내 예매', end: false, roles: ['USER'] },
  { to: '/seller', label: '판매 관리', end: true, roles: ['SELLER'] },
  { to: '/seller/settlements', label: '정산', end: false, roles: ['SELLER'] },
  { to: '/admin', label: '관리', end: false, roles: ['MASTER'] },
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
              // 휴대폰에서는 이름 대신 짧은 글자로 내 정보로 가는 길을 남긴다.
              <Link to="/me" className="navlink account-link" aria-label={`내 정보 (${me.data.name} · ${ROLE_LABEL[me.data.role]})`}>
                <span className="account-name">
                  {me.data.name} · {ROLE_LABEL[me.data.role]}
                </span>
                <span className="account-short">내 정보</span>
              </Link>
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

const ADMIN_SECTIONS = [
  { to: '/admin/venues', label: '공연장' },
  { to: '/admin/schedules', label: '회차 판매 중지' },
  { to: '/admin/coupons', label: '쿠폰 관리' },
  { to: '/admin/refunds', label: '환불 운영' },
  { to: '/admin/payouts', label: '지급 운영' },
];

/** 관리 영역의 하위 메뉴. 데스크톱은 왼쪽에 세로로, 휴대폰은 위쪽에 가로로 스크롤해 보인다. */
export function AdminLayout() {
  const nav = useRef<HTMLElement>(null);
  const { pathname } = useLocation();
  // 휴대폰 가로 탭에서 뒤쪽 메뉴에 있으면 지금 메뉴가 화면 밖에 있으므로 보이게 옮긴다.
  useEffect(() => {
    nav.current?.querySelector('.active')?.scrollIntoView({ block: 'nearest', inline: 'center' });
  }, [pathname]);
  return (
    <div className="section-shell">
      <nav ref={nav} aria-label="관리 메뉴" className="section-nav">
        {ADMIN_SECTIONS.map((section) => (
          <NavLink key={section.to} to={section.to} className={({ isActive }) => (isActive ? 'section-link active' : 'section-link')}>
            {section.label}
          </NavLink>
        ))}
      </nav>
      <div className="section-body">
        <Outlet />
      </div>
    </div>
  );
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
