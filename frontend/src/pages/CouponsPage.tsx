import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, getToken } from '../api/client';
import type { CouponDetail, PageC, UserCoupon } from '../api/types';
import { ErrorNotice, Loading } from '../components/Common';
import { couponConditions, couponTitle } from '../lib/coupons';
import { formatDateTime } from '../lib/format';

const STATUS: Record<string, string> = {
  AVAILABLE: '사용 가능',
  RESERVED: '예매에 사용 중',
  USED: '사용 완료',
  EXPIRED: '기간 만료',
};
export function CouponsPage() {
  const [tab, setTab] = useState<'open' | 'mine'>('open');
  const [category, setCategory] = useState<'GENERAL' | 'WELCOME'>('GENERAL');
  const [status, setStatus] = useState('ALL');
  const [notice, setNotice] = useState('');
  const client = useQueryClient();
  const open = useInfiniteQuery({
    queryKey: ['coupons', 'catalog', category, getToken()],
    enabled: tab === 'open',
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      api<PageC<CouponDetail>>(
        `/api/coupons?issueStatus=ISSUING&category=${category}&size=50&page=${pageParam}`,
      ),
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
  });
  const mine = useInfiniteQuery({
    queryKey: ['coupons', 'me', 'pages', status, getToken()],
    enabled: tab === 'mine',
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      api<PageC<UserCoupon>>(
        `/api/coupons/me?size=50&page=${pageParam}${status === 'ALL' ? '' : `&status=${status}`}`,
      ),
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
  });
  const issue = useMutation({
    mutationFn: (coupon: CouponDetail) => api(`/api/coupons/${coupon.couponId}/issue`, { method: 'POST' }),
    onSuccess: (_, coupon) => setNotice(`${coupon.name} 쿠폰을 받았어요. 내 쿠폰에서 확인할 수 있어요.`),
    onSettled: () => client.invalidateQueries({ queryKey: ['coupons'] }),
  });
  const list = tab === 'open' ? open : mine;
  const total = list.data?.pages[0]?.totalElements;
  return (
    <main className="content coupon-wallet">
      <div className="wallet-heading">
        <div>
          <span className="wallet-eyebrow">SAFE TICKET BENEFITS</span>
          <h1 className="section-marker">쿠폰함</h1>
          <p>공연에 맞는 혜택을 찾아보세요.</p>
        </div>
        {total !== undefined && <span className="wallet-total">{total}개의 쿠폰</span>}
      </div>
      <div className="wallet-tabs" aria-label="쿠폰 보기">
        <button type="button" aria-pressed={tab === 'open'} onClick={() => setTab('open')}>
          받을 수 있는 쿠폰
        </button>
        <button type="button" aria-pressed={tab === 'mine'} onClick={() => setTab('mine')}>
          내 쿠폰
        </button>
      </div>
      <div className="wallet-filters">
        {tab === 'open' ? (
          <>
            <button
              type="button"
              className="chip"
              aria-pressed={category === 'GENERAL'}
              onClick={() => setCategory('GENERAL')}
            >
              일반 쿠폰
            </button>
            <button
              type="button"
              className="chip"
              aria-pressed={category === 'WELCOME'}
              onClick={() => setCategory('WELCOME')}
            >
              웰컴 쿠폰
            </button>
          </>
        ) : (
          <div className="wallet-status">
            <label htmlFor="wallet-coupon-status">쿠폰 상태</label>
            <select
              id="wallet-coupon-status"
              className="input"
              value={status}
              onChange={(event) => setStatus(event.target.value)}
            >
              <option value="ALL">전체</option>
              {Object.entries(STATUS).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>
        )}
      </div>
      {notice && (
        <p className="wallet-notice" role="status">
          {notice}
        </p>
      )}
      {issue.isError && <ErrorNotice error={issue.error} />}
      {list.isPending && <Loading />}
      {list.isError && (
        <div className="stack">
          <ErrorNotice error={list.error} />
          <button type="button" className="btn btn-ghost" onClick={() => list.refetch()}>
            쿠폰 다시 불러오기
          </button>
        </div>
      )}
      {total === 0 && (
        <p className="home-empty">
          {tab === 'open' ? '지금 받을 수 있는 쿠폰이 없어요.' : '해당 상태의 쿠폰이 없어요.'}
        </p>
      )}
      <div className="wallet-grid">
        {tab === 'open'
          ? open.data?.pages
              .flatMap((page) => page.content)
              .map((coupon) => {
                const soldOut = coupon.remainingQuantity <= 0;
                return (
                  <section
                    className={`wallet-ticket ${coupon.category === 'WELCOME' ? 'welcome-ticket' : ''}`}
                    key={coupon.couponId}
                    aria-label={coupon.name}
                  >
                    <div className="wallet-ticket-body">
                      <span className="wallet-ticket-category">
                        {coupon.category === 'WELCOME' ? 'WELCOME' : 'COUPON'}
                      </span>
                      <strong className="wallet-discount">{couponTitle(coupon)}</strong>
                      <h2>{coupon.name}</h2>
                      <div className="wallet-conditions">
                        {couponConditions(coupon).map((line) => (
                          <p key={line}>{line}</p>
                        ))}
                      </div>
                      <p className="wallet-expiry">{formatDateTime(coupon.issueEndAt)}까지 발급</p>
                      <p className="wallet-expiry">
                        받은 날부터 {coupon.validDays}일 사용 · {coupon.remainingQuantity}장 남음
                      </p>
                    </div>
                    <div className="wallet-ticket-action">
                      <button
                        type="button"
                        className={coupon.issuedByMe || soldOut ? 'btn btn-ghost' : 'btn btn-primary'}
                        disabled={coupon.issuedByMe || soldOut || issue.isPending}
                        onClick={() => {
                          setNotice('');
                          issue.mutate(coupon);
                        }}
                      >
                        {coupon.issuedByMe
                          ? '받은 쿠폰'
                          : soldOut
                            ? '모두 소진됐어요'
                            : issue.isPending && issue.variables?.couponId === coupon.couponId
                              ? '받는 중'
                              : '쿠폰 받기'}
                      </button>
                    </div>
                  </section>
                );
              })
          : mine.data?.pages
              .flatMap((page) => page.content)
              .map((coupon) => (
                <section
                  key={coupon.userCouponId}
                  className={`wallet-ticket owned-ticket ${coupon.status === 'EXPIRED' || coupon.status === 'USED' ? 'inactive-ticket' : ''}`}
                  aria-label={coupon.name ?? '보유 쿠폰'}
                >
                  <div className="wallet-ticket-body">
                    <span className="wallet-ticket-category">MY COUPON</span>
                    <strong className="wallet-discount">{couponTitle(coupon)}</strong>
                    <h2>{coupon.name ?? '보유 쿠폰'}</h2>
                    <div className="wallet-conditions">
                      {couponConditions(coupon).map((line) => (
                        <p key={line}>{line}</p>
                      ))}
                    </div>
                    <p className="wallet-expiry">
                      {coupon.usedAt
                        ? `${formatDateTime(coupon.usedAt)} 사용`
                        : `${formatDateTime(coupon.expiresAt)}까지`}
                    </p>
                    {coupon.status === 'RESERVED' && (
                      <p className="wallet-expiry">현재 예매에서 사용 중이며, 결제 결과에 따라 처리돼요.</p>
                    )}
                  </div>
                  <div className="wallet-ticket-action">
                    <span
                      className={`wallet-state ${coupon.status === 'AVAILABLE' ? 'available-state' : ''}`}
                    >
                      {STATUS[coupon.status] ?? coupon.status}
                    </span>
                  </div>
                </section>
              ))}
      </div>
      {list.hasNextPage && (
        <div className="wallet-more">
          <button
            type="button"
            className="btn btn-ghost"
            disabled={list.isFetchingNextPage}
            onClick={() => list.fetchNextPage()}
          >
            {list.isFetchingNextPage ? '불러오는 중' : '쿠폰 더 보기'}
          </button>
        </div>
      )}
      <p className="wallet-footnote">
        쿠폰은 예매 한 건에 한 장만 사용해요. 적용 조건과 최종 할인 금액은 결제 화면에서 확인해 주세요.
      </p>
    </main>
  );
}
