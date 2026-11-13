import { NavLink } from 'react-router-dom';
import type { Role } from '../api/types';
export function AccountNav({ role = 'USER' }: { role?: Role }) {
  return (
    <nav className="account-sidebar" aria-label="내 계정 메뉴">
      <span>MY SAFE TICKET</span>
      <NavLink to="/me">내 정보</NavLink>
      {role === 'USER' ? (
        <>
          <NavLink to="/reservations" end>
            내 예매
          </NavLink>
          <NavLink to="/coupons">쿠폰함</NavLink>
        </>
      ) : (
        <NavLink to={role === 'SELLER' ? '/seller' : '/admin'}>
          {role === 'SELLER' ? '판매자 센터' : '관리자 센터'}
        </NavLink>
      )}
    </nav>
  );
}
