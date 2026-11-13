import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { api, getToken, setToken } from '../api/client';
import type { Role, User } from '../api/types';
import { ErrorNotice, Loading } from './Common';
import { Brand } from './Brand';
import { HeaderSearch } from './HeaderSearch';
import { ThemeToggle } from './ThemeToggle';

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

export const ROLE_LABEL: Record<Role, string> = {
  USER: '회원',
  SELLER: '판매자',
  MASTER: '관리자',
};

// 예매·쿠폰 발급은 USER만 할 수 있어 다른 역할에는 메뉴를 보이지 않는다. 판매·관리 메뉴는 그 역할에만 보인다.
const links: { to: string; label: string; roles: Role[] }[] = [
  { to: '/', label: '공연', roles: ['USER', 'SELLER', 'MASTER'] },
  { to: '/#ticket-openings', label: '티켓 오픈', roles: ['USER'] },
  { to: '/seller', label: '판매 관리', roles: ['SELLER'] },
  { to: '/admin', label: '관리', roles: ['MASTER'] },
];
const memberLinks = [
  { to: '/coupons', label: '쿠폰함' },
  { to: '/reservations', label: '내 예매' },
];

export function Layout() {
  const loggedIn = useLoggedIn();
  const me = useMe();
  const navigate = useNavigate();
  const location = useLocation();
  useEffect(() => {
    if (!location.hash) return;
    const frame = requestAnimationFrame(() =>
      document.getElementById(location.hash.slice(1))?.scrollIntoView({ block: 'start' }),
    );
    return () => cancelAnimationFrame(frame);
  }, [location.key, location.hash]);
  // 공개 탐색은 로그인 전에도 제공하고, 개인 메뉴는 회원 역할이 확인된 뒤에만 표시한다.
  const role = me.data?.role ?? 'USER';
  const visible = links.filter((link) => link.roles.includes(role));
  const menuActive = (to: string) =>
    to === '/#ticket-openings'
      ? location.pathname === '/' && location.hash === '#ticket-openings'
      : to === '/'
        ? (location.pathname === '/' && location.hash !== '#ticket-openings') ||
          location.pathname.startsWith('/performances/')
        : location.pathname === to || location.pathname.startsWith(to + '/');
  return (
    <div className="app">
      <a className="skip-link" href="#main-content">
        본문 바로가기
      </a>
      <header className="topbar">
        <Link to="/" className="brand" aria-label="safe ticket 홈">
          <Brand />
        </Link>
        <HeaderSearch />
        <div className="header-tools">
          {loggedIn ? (
            <span className="account">
              {me.data?.role === 'USER' && memberLinks.map((link) => (
                <Link
                  key={link.to}
                  to={link.to}
                  className={menuActive(link.to) ? 'navlink active' : 'navlink'}
                  aria-current={menuActive(link.to) ? 'page' : undefined}
                >
                  {link.label}
                </Link>
              ))}
              {me.data && (
                // 휴대폰에서는 이름 대신 짧은 글자로 내 정보로 가는 길을 남긴다.
                <Link
                  to="/me"
                  className="navlink account-link"
                  aria-label={`내 정보 (${me.data.name} · ${ROLE_LABEL[me.data.role]})`}
                >
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
            <span className="account">
              <Link to="/login" className="navlink">
                로그인
              </Link>
              <Link to="/signup" className="navlink signup-link">
                회원가입
              </Link>
            </span>
          )}
          <ThemeToggle />
        </div>
      </header>
      <div className="navigation-bar">
        <nav aria-label="주요 메뉴" className="topnav">
          {visible.map((link) => (
            <Link
              key={link.to}
              to={link.to}
              className={menuActive(link.to) ? 'navlink active' : 'navlink'}
              aria-current={menuActive(link.to) ? 'page' : undefined}
            >
              {link.label}
            </Link>
          ))}
        </nav>
      </div>
      <div id="main-content" className="page-outlet" tabIndex={-1}>
        <Outlet />
      </div>
      <footer className="site-footer">
        <Link to="/" className="brand" aria-label="safe ticket 홈">
          <Brand />
        </Link>
        <p>공연 티켓 예매 플랫폼</p>
        <span>© {new Date().getFullYear()} SafeTicket</span>
      </footer>
      <nav aria-label="주요 메뉴" className="bottomnav">
        {visible.map((link) => (
          <Link
            key={link.to}
            to={link.to}
            className={menuActive(link.to) ? 'navlink active' : 'navlink'}
            aria-current={menuActive(link.to) ? 'page' : undefined}
          >
            {link.label}
          </Link>
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

/** paths 중 하나로 시작하면 그 메뉴를 켠다. 판매자 공연 관리는 /seller 자체와 공연·회차 화면을 함께 맡는다. */
interface Section {
  to: string;
  label: string;
  paths: string[];
}

const ADMIN_SECTIONS: Section[] = [
  { to: '/admin/venues', label: '공연장 관리', paths: ['/admin/venues'] },
  {
    to: '/admin/schedules',
    label: '회차 운영',
    paths: ['/admin/schedules'],
  },
  { to: '/admin/waiting-rooms', label: '대기열 관리', paths: ['/admin/waiting-rooms'] },
  { to: '/admin/coupons', label: '쿠폰 관리', paths: ['/admin/coupons'] },
  { to: '/admin/refunds', label: '환불 운영', paths: ['/admin/refunds'] },
  { to: '/admin/payouts', label: '지급 운영', paths: ['/admin/payouts'] },
];

const SELLER_SECTIONS: Section[] = [
  {
    to: '/seller',
    label: '공연 관리',
    paths: ['/seller/performances'],
  },
  { to: '/seller/schedules', label: '회차 관리', paths: ['/seller/schedules'] },
  { to: '/seller/settlements', label: '정산 내역', paths: ['/seller/settlements'] },
  { to: '/seller/payout-account', label: '지급 계좌', paths: ['/seller/payout-account'] },
];

/** 역할 영역의 하위 메뉴. 데스크톱은 왼쪽에 세로로, 휴대폰은 위쪽에 가로로 스크롤해 보인다. */
function SectionLayout({
  label,
  sections,
  seller = false,
  admin = false,
}: {
  label: string;
  sections: Section[];
  seller?: boolean;
  admin?: boolean;
}) {
  const nav = useRef<HTMLElement>(null);
  const { pathname } = useLocation();
  const active = (section: Section) =>
    pathname === section.to || section.paths.some((path) => pathname.startsWith(path));
  // 휴대폰 가로 탭에서 뒤쪽 메뉴에 있으면 지금 메뉴가 화면 밖에 있으므로 보이게 옮긴다.
  useEffect(() => {
    nav.current?.querySelector('.active')?.scrollIntoView({ block: 'nearest', inline: 'center' });
    if (seller || admin) window.scrollTo({ top: 0 });
  }, [pathname, seller, admin]);
  return (
    <div
      className={
        admin
          ? 'section-shell seller-shell admin-shell'
          : seller
            ? 'section-shell seller-shell'
            : 'section-shell'
      }
    >
      <nav ref={nav} aria-label={label} className="section-nav">
        {seller && <span className="seller-nav-caption">판매자 센터</span>}
        {admin && <span className="seller-nav-caption">관리자 센터</span>}
        {sections.map((section) => (
          <Link
            key={section.to}
            to={section.to}
            className={active(section) ? 'section-link active' : 'section-link'}
            aria-current={active(section) ? 'page' : undefined}
          >
            {section.label}
          </Link>
        ))}
      </nav>
      <div className="section-body">
        <Outlet />
      </div>
    </div>
  );
}

export function AdminLayout() {
  return <SectionLayout label="관리 메뉴" sections={ADMIN_SECTIONS} admin />;
}

export function SellerLayout() {
  return <SectionLayout label="판매 관리 메뉴" sections={SELLER_SECTIONS} seller />;
}

/** 역할이 맞지 않으면 화면 대신 안내를 보인다. 서버도 같은 역할을 검사하므로 이것은 길 안내일 뿐이다. */
export function RequireRole({ roles }: { roles: Role[] }) {
  const me = useMe();
  if (me.isPending)
    return (
      <main className="content">
        <Loading />
      </main>
    );
  if (me.isError)
    return (
      <main className="content">
        <ErrorNotice error={me.error} />
      </main>
    );
  if (!roles.includes(me.data.role)) {
    return (
      <main className="content narrow">
        <p className="notice">{roles.map((role) => ROLE_LABEL[role]).join('·')} 계정으로 이용할 수 있어요.</p>
      </main>
    );
  }
  return <Outlet />;
}
