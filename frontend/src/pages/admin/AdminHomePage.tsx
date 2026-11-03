import { Link } from 'react-router-dom';
import { Title } from '../../components/Common';

const MENUS = [
  { to: '/admin/venues', name: '공연장', note: '공연장을 만들고 등급·좌석을 등록해요.' },
  { to: '/admin/schedules', name: '회차 판매 중지', note: '회차 판매를 잠시 멈추거나 다시 열어요.' },
  { to: '/admin/coupons', name: '쿠폰 관리', note: '쿠폰을 만들고 수정·발급 중단·삭제해요.' },
  { to: '/admin/refunds', name: '환불 운영', note: '멈춘 환불을 PG로 확인하고 재시도·재개해요.' },
  { to: '/admin/payouts', name: '지급 운영', note: '판매자 지급 실패를 재시도하고 미회수 차감을 봐요.' },
];

export function AdminHomePage() {
  return (
    <main className="content medium">
      <Title en="Admin" ko="관리" />
      <nav className="hub" aria-label="관리 메뉴">
        {MENUS.map((menu) => (
          <Link key={menu.to} to={menu.to}>
            <span className="name">{menu.name}</span>
            <span className="muted">{menu.note}</span>
          </Link>
        ))}
      </nav>
    </main>
  );
}
