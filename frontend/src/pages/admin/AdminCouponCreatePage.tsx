import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api, getToken } from '../../api/client';
import type { Coupon, CouponInput } from '../../api/manageTypes';
import { ErrorNotice } from '../../components/Common';
import { CouponPolicyFields } from '../../components/CouponPolicyFields';
import { CouponPolicyPreview } from '../../components/CouponPolicyPreview';
import { createCouponBody, newCouponForm } from '../../lib/adminCoupons';
export function AdminCouponCreatePage() {
  const client = useQueryClient(),
    navigate = useNavigate();
  const [form, setForm] = useState(newCouponForm),
    [validation, setValidation] = useState<string | null>(null);
  const create = useMutation({
    mutationFn: (body: CouponInput) => api<Coupon>('/api/coupons', { method: 'POST', body }),
    onSuccess: (result) => {
      client.setQueryData(['admin-coupon', getToken(), String(result.couponId)], result);
      client.invalidateQueries({ queryKey: ['admin-coupons'] });
      client.invalidateQueries({ queryKey: ['coupons'] });
      navigate(`/admin/coupons/${result.couponId}`, { replace: true });
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (create.isPending) return;
    try {
      const body = createCouponBody(form);
      setValidation(null);
      create.mutate(body);
    } catch (error) {
      setValidation((error as Error).message);
    }
  };
  return (
    <main className="content seller-page admin-page stack">
      <Link className="seller-back" to="/admin/coupons">
        ‹ 쿠폰 목록
      </Link>
      <header className="seller-page-heading">
        <div>
          <h1 className="section-marker">쿠폰 등록</h1>
          <p>할인 조건과 발급 기간을 설정하세요.</p>
        </div>
      </header>
      <div className="admin-coupon-layout">
        <section className="panel stack admin-accent-panel">
          <form className="stack" onSubmit={submit}>
            {validation && (
              <p className="notice" role="alert">
                {validation}
              </p>
            )}
            {create.isError && <ErrorNotice error={create.error} />}
            <CouponPolicyFields value={form} onChange={setForm} disabled={create.isPending} />
            <button className="btn btn-primary" disabled={create.isPending}>
              {create.isPending ? '등록 중' : '쿠폰 등록'}
            </button>
          </form>
        </section>
        <CouponPolicyPreview value={form} />
      </div>
    </main>
  );
}
