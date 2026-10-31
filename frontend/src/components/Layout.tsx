import { useEffect, useState } from 'react';
import { Link, NavLink, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { getToken, setToken } from '../api/client';

export function useLoggedIn(): boolean {
  const [loggedIn, setLoggedIn] = useState(() => getToken() !== null);
  useEffect(() => {
    const update = () => setLoggedIn(getToken() !== null);
    window.addEventListener('ticket-auth-changed', update);
    return () => window.removeEventListener('ticket-auth-changed', update);
  }, []);
  return loggedIn;
}

const links = [
  { to: '/', label: '공연', end: true },
  { to: '/coupons', label: '쿠폰함', end: false },
  { to: '/reservations', label: '내 예매', end: false },
];

function navClass({ isActive }: { isActive: boolean }) {
  return isActive ? 'navlink active' : 'navlink';
}

export function Layout() {
  const loggedIn = useLoggedIn();
  const navigate = useNavigate();
  return (
    <div className="app">
      <header className="topbar">
        <Link to="/" className="brand">
          Ticket
        </Link>
        <nav aria-label="주요 메뉴" className="topnav">
          {links.map((link) => (
            <NavLink key={link.to} to={link.to} end={link.end} className={navClass}>
              {link.label}
            </NavLink>
          ))}
        </nav>
        {loggedIn ? (
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
        ) : (
          <Link to="/login" className="navlink">
            로그인
          </Link>
        )}
      </header>
      <Outlet />
      <nav aria-label="주요 메뉴" className="bottomnav">
        {links.map((link) => (
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
    return <Navigate to={`/login?next=${encodeURIComponent(location.pathname)}`} replace />;
  }
  return <Outlet />;
}
