import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { api, getToken } from '../../api/client';
import type { Coupon, CouponIssueStatus } from '../../api/manageTypes';
import type { PageC } from '../../api/types';
import { ErrorNotice, Loading } from '../../components/Common';
import { couponTitle, couponConditions } from '../../lib/coupons';
import { ISSUE_STATUS_LABEL } from '../../lib/adminCoupons';
import { formatDateTime } from '../../lib/format';
export function AdminCouponsPage() {
  const [params, setParams] = useSearchParams();
  const category = params.get('category') === 'WELCOME' ? 'WELCOME' : 'GENERAL';
  const status = (
    ['UPCOMING', 'ISSUING', 'ENDED'].includes(params.get('issueStatus') ?? '')
      ? params.get('issueStatus')
      : 'ISSUING'
  ) as CouponIssueStatus;
  const sort = ['createdAt,DESC', 'issueStartAt,ASC', 'issueEndAt,ASC'].includes(params.get('sort') ?? '')
    ? params.get('sort')!
    : 'createdAt,DESC';
  const raw = Number(params.get('page') ?? 0),
    page = Number.isSafeInteger(raw) && raw >= 0 ? raw : 0;
  const request = new URLSearchParams({
    category,
    issueStatus: status,
    sort,
    page: String(page),
    size: '10',
  });
  const query = useQuery({
    queryKey: ['admin-coupons', getToken(), request.toString()],
    queryFn: () => api<PageC<Coupon>>(`/api/coupons?${request}`),
  });
  const change = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    next.set(key, value);
    if (key !== 'page') next.delete('page');
    setParams(next, { preventScrollReset: true });
  };
  return (
    <main className="content seller-page admin-page stack">
      <header className="seller-page-heading">
        <div>
          <h1 className="section-marker">쿠폰 관리</h1>
          <p>일반·웰컴 쿠폰의 할인 정책과 발급 상태를 관리하세요.</p>
        </div>
        <Link className="btn btn-primary" to="/admin/coupons/new">
          ＋ 쿠폰 등록
        </Link>
      </header>
      <nav className="settlement-tabs" aria-label="쿠폰 구분">
        {[
          { value: 'GENERAL', label: '일반 쿠폰' },
          { value: 'WELCOME', label: '웰컴 쿠폰' },
        ].map((tab) => (
          <button
            type="button"
            key={tab.value}
            aria-pressed={category === tab.value}
            onClick={() => change('category', tab.value)}
          >
            {tab.label}
          </button>
        ))}
      </nav>
      <section className="panel stack">
        <div className="admin-coupon-toolbar">
          <div className="booking-filters" aria-label="발급 상태">
            {(['ISSUING', 'UPCOMING', 'ENDED'] as const).map((value) => (
              <button
                type="button"
                className="chip"
                key={value}
                aria-pressed={status === value}
                onClick={() => change('issueStatus', value)}
              >
                {ISSUE_STATUS_LABEL[value]}
              </button>
            ))}
          </div>
          <select
            aria-label="쿠폰 정렬"
            className="input"
            value={sort}
            onChange={(e) => change('sort', e.target.value)}
          >
            <option value="createdAt,DESC">최근 등록순</option>
            <option value="issueStartAt,ASC">발급 시작순</option>
            <option value="issueEndAt,ASC">발급 종료순</option>
          </select>
          <button type="button" className="chip" disabled={query.isFetching} onClick={() => query.refetch()}>
            새로고침
          </button>
        </div>
        {query.isPending && <Loading />}
        {query.isError && <ErrorNotice error={query.error} />}
        {query.isError && query.data && (
          <p className="account-hint">마지막 조회 결과입니다. 최신 상태를 다시 확인해 주세요.</p>
        )}
        {query.data && (
          <>
            <p className="account-hint">총 {query.data.totalElements}개</p>
            <div className="seller-table-scroll">
              <table className="seller-table admin-coupon-table">
                <thead>
                  <tr>
                    <th>쿠폰 · 할인 조건</th>
                    <th>발급 기간</th>
                    <th>남은 수량 / 총 수량</th>
                    <th>상태</th>
                    <th>관리</th>
                  </tr>
                </thead>
                <tbody>
                  {query.data.content.map((coupon) => (
                    <tr key={coupon.couponId}>
                      <td>
                        <div className="admin-coupon-list-title">
                          <span className="admin-coupon-symbol" aria-hidden="true">
                            %
                          </span>
                          <div>
                            <Link
                              className="seller-performance-name"
                              to={`/admin/coupons/${coupon.couponId}`}
                              state={{ couponReturn: `/admin/coupons?${request}` }}
                            >
                              {coupon.name}
                            </Link>
                            <span className="seller-cell-meta">
                              ID {coupon.couponId} · {couponTitle(coupon)}
                            </span>
                            <span className="seller-cell-meta">{couponConditions(coupon).join(' · ')}</span>
                          </div>
                        </div>
                      </td>
                      <td>
                        {formatDateTime(coupon.issueStartAt)}
                        <span className="seller-cell-meta">{formatDateTime(coupon.issueEndAt)}</span>
                      </td>
                      <td>
                        {coupon.remainingQuantity.toLocaleString('ko-KR')} /{' '}
                        {coupon.totalQuantity.toLocaleString('ko-KR')}
                        <span className="seller-cell-meta">발급 후 {coupon.validDays}일</span>
                      </td>
                      <td>
                        <span
                          className={`seller-state ${coupon.issueStatus === 'ISSUING' ? 'ready' : 'draft'}`}
                        >
                          {ISSUE_STATUS_LABEL[coupon.issueStatus]}
                        </span>
                      </td>
                      <td>
                        <Link
                          className="chip"
                          to={`/admin/coupons/${coupon.couponId}`}
                          state={{ couponReturn: `/admin/coupons?${request}` }}
                        >
                          관리 ›
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {query.data.content.length === 0 && (
              <p className="seller-empty">{ISSUE_STATUS_LABEL[status]} 쿠폰이 없어요.</p>
            )}
            {query.data.totalPages > 1 && (
              <nav className="pager" aria-label="쿠폰 페이지">
                <button
                  className="chip"
                  type="button"
                  disabled={page === 0 || query.isFetching}
                  onClick={() => change('page', String(page - 1))}
                >
                  이전
                </button>
                <span>
                  {page + 1} / {query.data.totalPages}
                </span>
                <button
                  className="chip"
                  type="button"
                  disabled={page + 1 >= query.data.totalPages || query.isFetching}
                  onClick={() => change('page', String(page + 1))}
                >
                  다음
                </button>
              </nav>
            )}
          </>
        )}
      </section>
    </main>
  );
}
