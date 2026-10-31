import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../api/client';
import type { CouponDetail, PageC, UserCoupon } from '../api/types';
import { ErrorNotice, Loading, Title } from '../components/Common';
import { formatDate, won } from '../lib/format';

const STATUS: Record<string, { label: string; className: string }> = {
  AVAILABLE: { label: '사용 가능', className: 'pill on' },
  RESERVED: { label: '예매에 사용 중', className: 'pill solid' },
  USED: { label: '사용 완료', className: 'pill' },
  EXPIRED: { label: '기간 만료', className: 'pill' },
};

export function CouponsPage() {
  const [tab, setTab] = useState<'open' | 'mine'>('open');
  const client = useQueryClient();
  const open = useQuery({
    queryKey: ['coupons', 'open'],
    enabled: tab === 'open',
    queryFn: () => api<PageC<CouponDetail>>('/api/coupons?issueStatus=ISSUING&size=50'),
  });
  const mine = useQuery({
    queryKey: ['coupons', 'me', 'ALL'],
    enabled: tab === 'mine',
    queryFn: () => api<PageC<UserCoupon>>('/api/coupons/me?size=50'),
  });
  const issue = useMutation({
    mutationFn: (couponId: number) => api(`/api/coupons/${couponId}/issue`, { method: 'POST' }),
    onSettled: () => client.invalidateQueries({ queryKey: ['coupons'] }),
  });
  return (
    <main className="content medium">
      <Title en="Coupons" ko="쿠폰함" />
      <div className="tabs">
        <button type="button" className="tab" aria-pressed={tab === 'open'} onClick={() => setTab('open')}>
          받을 수 있는 쿠폰
        </button>
        <button type="button" className="tab" aria-pressed={tab === 'mine'} onClick={() => setTab('mine')}>
          내 쿠폰
        </button>
      </div>
      {issue.isError && <div style={{ marginBottom: 16 }}><ErrorNotice error={issue.error} /></div>}
      {tab === 'open' && (
        <>
          {open.isPending && <Loading />}
          {open.isError && <ErrorNotice error={open.error} />}
          {open.data?.content.length === 0 && <p className="empty">지금 받을 수 있는 쿠폰이 없어요.</p>}
          <div className="coupon-grid">
            {open.data?.content.map((coupon) => {
              const soldOut = !coupon.issuedByMe && coupon.remainingQuantity === 0;
              return (
                <section key={coupon.couponId} className="coupon">
                  <div>
                    <div className="amount num">{won(coupon.discountAmount)}</div>
                    <div>{coupon.name}</div>
                    <div className="muted num" style={{ fontSize: 13 }}>
                      {formatDate(coupon.issueEndAt)}까지 발급 · {coupon.remainingQuantity}장 남음
                    </div>
                  </div>
                  <button
                    type="button"
                    className={coupon.issuedByMe || soldOut ? 'btn btn-ghost' : 'btn btn-primary'}
                    disabled={coupon.issuedByMe || soldOut || issue.isPending}
                    onClick={() => issue.mutate(coupon.couponId)}
                  >
                    {coupon.issuedByMe ? '받은 쿠폰' : soldOut ? '모두 소진됐어요' : '쿠폰 받기'}
                  </button>
                </section>
              );
            })}
          </div>
        </>
      )}
      {tab === 'mine' && (
        <>
          {mine.isPending && <Loading />}
          {mine.isError && <ErrorNotice error={mine.error} />}
          {mine.data?.content.length === 0 && <p className="empty">받은 쿠폰이 없어요.</p>}
          <div className="coupon-grid">
            {mine.data?.content.map((coupon) => {
              const status = STATUS[coupon.status] ?? { label: coupon.status, className: 'pill' };
              return (
                <section key={coupon.userCouponId} className="coupon">
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
                    <div className="amount num">{won(coupon.discountAmount)}</div>
                    <span className={status.className}>{status.label}</span>
                  </div>
                  <div>{coupon.name}</div>
                  <div className="muted num" style={{ fontSize: 13 }}>
                    {coupon.usedAt ? `${formatDate(coupon.usedAt)} 사용` : `${formatDate(coupon.expiresAt)}까지`}
                  </div>
                </section>
              );
            })}
          </div>
        </>
      )}
    </main>
  );
}
