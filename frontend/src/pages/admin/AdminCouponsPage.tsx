import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FormEvent, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api/client';
import type { Coupon, CouponInput, CouponIssueStatus } from '../../api/manageTypes';
import type { PageC } from '../../api/types';
import { ErrorNotice, Loading, Title } from '../../components/Common';
import { Done, Field, Panel } from '../../components/Form';
import { formatDateTime, seoulInputToLocal, toSeoulInput, won } from '../../lib/format';

export const ISSUE_STATUS_LABEL: Record<CouponIssueStatus, string> = {
  UPCOMING: '발급 예정',
  ISSUING: '발급 중',
  ENDED: '발급 종료',
};

const STATUSES: CouponIssueStatus[] = ['ISSUING', 'UPCOMING', 'ENDED'];

export function AdminCouponsPage() {
  const [status, setStatus] = useState<CouponIssueStatus>('ISSUING');
  const [page, setPage] = useState(0);
  const params = new URLSearchParams({ issueStatus: status, page: String(page), size: '10' });
  const list = useQuery({
    queryKey: ['admin-coupons', params.toString()],
    queryFn: () => api<PageC<Coupon>>(`/api/coupons?${params}`),
  });
  return (
    <main className="content medium stack" style={{ gap: 24 }}>
      <Title en="Coupons" ko="쿠폰 관리" />
      <CreateCoupon />
      <section className="stack">
        <div className="chips">
          {STATUSES.map((value) => (
            <button
              key={value}
              type="button"
              className="chip"
              aria-pressed={status === value}
              onClick={() => {
                setStatus(value);
                setPage(0);
              }}
            >
              {ISSUE_STATUS_LABEL[value]}
            </button>
          ))}
        </div>
        {list.isPending && <Loading />}
        {list.isError && <ErrorNotice error={list.error} />}
        {list.data?.content.length === 0 && <p className="empty">{ISSUE_STATUS_LABEL[status]} 쿠폰이 없어요.</p>}
        {list.data?.content.map((coupon) => (
          <Link key={coupon.couponId} to={`/admin/coupons/${coupon.couponId}`} className="item">
            <span>
              <span className="name">{coupon.name}</span>
              <br />
              <span className="sub num">
                {won(coupon.discountAmount)} 할인 · 남은 수량 {coupon.remainingQuantity}/{coupon.totalQuantity} ·{' '}
                {formatDateTime(coupon.issueStartAt)} ~ {formatDateTime(coupon.issueEndAt)}
              </span>
            </span>
            <span className="pill">#{coupon.couponId}</span>
          </Link>
        ))}
        {list.data && list.data.totalPages > 1 && (
          <div className="pager">
            <button type="button" className="chip" disabled={page === 0} onClick={() => setPage(page - 1)}>
              이전
            </button>
            <span className="num">
              {page + 1} / {list.data.totalPages}
            </span>
            <button type="button" className="chip" disabled={page + 1 >= list.data.totalPages} onClick={() => setPage(page + 1)}>
              다음
            </button>
          </div>
        )}
      </section>
    </main>
  );
}

function CreateCoupon() {
  const client = useQueryClient();
  const [form, setForm] = useState({
    name: '',
    discountAmount: '3000',
    totalQuantity: '100',
    issueStartAt: toSeoulInput(undefined, 0),
    issueEndAt: toSeoulInput(undefined, 7 * 24 * 60),
    validDays: '30',
  });
  const create = useMutation({
    mutationFn: () =>
      api<Coupon>('/api/coupons', {
        method: 'POST',
        body: {
          name: form.name,
          discountAmount: Number(form.discountAmount),
          totalQuantity: Number(form.totalQuantity),
          issueStartAt: seoulInputToLocal(form.issueStartAt),
          issueEndAt: seoulInputToLocal(form.issueEndAt),
          validDays: Number(form.validDays),
        } satisfies CouponInput,
      }),
    onSuccess: () => {
      // 같은 쿠폰을 한 번 더 만들지 않도록 이름을 비운다. 나머지 값은 비슷한 쿠폰을 이어 만들 때 쓰게 둔다.
      setForm((current) => ({ ...current, name: '' }));
      client.invalidateQueries({ queryKey: ['admin-coupons'] });
    },
  });
  const bind = (name: keyof typeof form) => ({
    id: `coupon-${name}`,
    className: 'input',
    required: true,
    value: form[name],
    onChange: (e: { target: { value: string } }) => setForm({ ...form, [name]: e.target.value }),
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    create.mutate();
  };
  return (
    <Panel title="쿠폰 만들기" note="시작 시각이 지금보다 앞이면 서버가 지금으로 맞춰 저장해요. 시각은 서울 기준이에요.">
      <form className="stack" onSubmit={submit}>
        {create.isError && <ErrorNotice error={create.error} />}
        {create.isSuccess && (
          <Done>
            쿠폰 #{create.data.couponId}을 만들었어요. <Link to={`/admin/coupons/${create.data.couponId}`}>상세 보기</Link>
          </Done>
        )}
        <div className="form-grid">
          <Field id="coupon-name" label="이름 (1~100자)">
            <input {...bind('name')} maxLength={100} />
          </Field>
          <Field id="coupon-discountAmount" label="할인 금액(원)">
            <input {...bind('discountAmount')} type="number" min={1} />
          </Field>
          <Field id="coupon-totalQuantity" label="총 수량">
            <input {...bind('totalQuantity')} type="number" min={1} />
          </Field>
          <Field id="coupon-validDays" label="유효 기간(일)" hint="발급받은 날부터 셉니다.">
            <input {...bind('validDays')} type="number" min={1} />
          </Field>
          <Field id="coupon-issueStartAt" label="발급 시작">
            <input {...bind('issueStartAt')} type="datetime-local" />
          </Field>
          <Field id="coupon-issueEndAt" label="발급 종료" hint="시작보다 뒤이고 지금보다 뒤여야 해요.">
            <input {...bind('issueEndAt')} type="datetime-local" />
          </Field>
        </div>
        <button className="btn btn-primary" disabled={create.isPending}>
          쿠폰 만들기
        </button>
      </form>
    </Panel>
  );
}
