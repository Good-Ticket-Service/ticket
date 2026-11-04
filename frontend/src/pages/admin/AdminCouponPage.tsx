import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FormEvent, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { Coupon, CouponInput } from '../../api/manageTypes';
import { ErrorNotice, Loading, Title } from '../../components/Common';
import { ConfirmDialog, Done, Facts, Field, Panel } from '../../components/Form';
import { formatDateTime, seoulInputToLocal, toSeoulInput, won } from '../../lib/format';
import { ISSUE_STATUS_LABEL } from './AdminCouponsPage';

type Form = Record<keyof CouponInput, string>;

function toForm(coupon: Coupon): Form {
  return {
    name: coupon.name,
    discountAmount: String(coupon.discountAmount),
    totalQuantity: String(coupon.totalQuantity),
    issueStartAt: toSeoulInput(coupon.issueStartAt),
    issueEndAt: toSeoulInput(coupon.issueEndAt),
    validDays: String(coupon.validDays),
  };
}

/** 수정은 바꿀 항목만 보낸다. 아무것도 보내지 않으면 서버가 400으로 거절한다. */
function changes(form: Form, before: Form): Partial<CouponInput> {
  const body: Partial<CouponInput> = {};
  if (form.name !== before.name) body.name = form.name;
  if (form.discountAmount !== before.discountAmount) body.discountAmount = Number(form.discountAmount);
  if (form.totalQuantity !== before.totalQuantity) body.totalQuantity = Number(form.totalQuantity);
  if (form.issueStartAt !== before.issueStartAt) body.issueStartAt = seoulInputToLocal(form.issueStartAt);
  if (form.issueEndAt !== before.issueEndAt) body.issueEndAt = seoulInputToLocal(form.issueEndAt);
  if (form.validDays !== before.validDays) body.validDays = Number(form.validDays);
  return body;
}

export function AdminCouponPage() {
  const { couponId } = useParams();
  const navigate = useNavigate();
  const client = useQueryClient();
  const path = `/api/coupons/${couponId}`;
  const coupon = useQuery({ queryKey: ['admin-coupon', couponId], queryFn: () => api<Coupon>(path) });
  const [form, setForm] = useState<Form | null>(null);
  const [asking, setAsking] = useState(false);
  useEffect(() => {
    if (coupon.data) setForm(toForm(coupon.data));
  }, [coupon.data]);

  const refresh = (data: Coupon) => {
    client.setQueryData(['admin-coupon', couponId], data);
    client.invalidateQueries({ queryKey: ['admin-coupons'] });
  };
  const update = useMutation({
    mutationFn: (body: Partial<CouponInput>) => api<Coupon>(path, { method: 'PATCH', body }),
    onSuccess: refresh,
  });
  const close = useMutation({ mutationFn: () => api<Coupon>(`${path}/close`, { method: 'POST' }), onSuccess: refresh });
  const remove = useMutation({
    mutationFn: () => api(path, { method: 'DELETE' }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['admin-coupons'] });
      navigate('/admin/coupons', { replace: true });
    },
  });

  if (coupon.isPending) return <main className="content narrow"><Loading /></main>;
  if (coupon.isError) return <main className="content narrow"><ErrorNotice error={coupon.error} /></main>;
  const item = coupon.data;
  const before = toForm(item);
  const body = form ? changes(form, before) : {};
  const submit = (event: FormEvent) => {
    event.preventDefault();
    update.mutate(body);
  };
  const bind = (name: keyof Form) => ({
    id: `edit-${name}`,
    className: 'input',
    required: true,
    value: form?.[name] ?? '',
    onChange: (e: { target: { value: string } }) => form && setForm({ ...form, [name]: e.target.value }),
  });
  const started = item.issueStatus !== 'UPCOMING';
  const ended = item.issueStatus === 'ENDED';
  return (
    <main className="content narrow stack" style={{ gap: 24 }}>
      <Link to="/admin/coupons" className="navlink" style={{ paddingLeft: 0 }}>
        ← 쿠폰 목록
      </Link>
      <Title en="Coupon" ko={`쿠폰 #${item.couponId}`} />
      <Panel title={item.name}>
        <Facts
          items={[
            ['상태', ISSUE_STATUS_LABEL[item.issueStatus]],
            ['할인 금액', won(item.discountAmount)],
            ['남은 수량', `${item.remainingQuantity} / ${item.totalQuantity}`],
            ['발급 기간', `${formatDateTime(item.issueStartAt)} ~ ${formatDateTime(item.issueEndAt)}`],
            ['유효 기간', `${item.validDays}일`],
          ]}
        />
      </Panel>
      <Panel
        title="수정"
        note={
          ended
            ? '발급이 끝난 쿠폰은 이름만 바꿀 수 있어요.'
            : started
              ? '발급이 시작된 쿠폰은 이름, 발급 종료, 총 수량(발급한 수 이상)만 바꿀 수 있어요.'
              : '바꾼 항목만 보내요.'
        }
      >
        <form className="stack" onSubmit={submit}>
          {update.isError && <ErrorNotice error={update.error} />}
          {update.isSuccess && <Done>수정했어요.</Done>}
          <div className="form-grid">
            <Field id="edit-name" label="이름">
              <input {...bind('name')} maxLength={100} />
            </Field>
            <Field id="edit-discountAmount" label="할인 금액(원)">
              <input {...bind('discountAmount')} type="number" min={1} disabled={started} />
            </Field>
            <Field id="edit-totalQuantity" label="총 수량">
              <input {...bind('totalQuantity')} type="number" min={1} disabled={ended} />
            </Field>
            <Field id="edit-validDays" label="유효 기간(일)">
              <input {...bind('validDays')} type="number" min={1} disabled={started} />
            </Field>
            <Field id="edit-issueStartAt" label="발급 시작">
              <input {...bind('issueStartAt')} type="datetime-local" disabled={started} />
            </Field>
            <Field id="edit-issueEndAt" label="발급 종료">
              <input {...bind('issueEndAt')} type="datetime-local" disabled={ended} />
            </Field>
          </div>
          <button className="btn btn-primary" disabled={update.isPending || Object.keys(body).length === 0}>
            {Object.keys(body).length === 0 ? '바꾼 항목이 없어요' : `${Object.keys(body).length}개 항목 수정`}
          </button>
        </form>
      </Panel>
      <Panel title="발급 중단·삭제" note="삭제는 한 장도 발급하지 않은 쿠폰만 할 수 있어요.">
        {close.isError && <ErrorNotice error={close.error} />}
        {remove.isError && <ErrorNotice error={remove.error} />}
        {close.isSuccess && <Done>발급을 중단했어요.</Done>}
        <div className="actions">
          <button type="button" className="btn btn-ghost" disabled={ended || close.isPending} onClick={() => close.mutate()}>
            발급 중단
          </button>
          <button
            type="button"
            className="btn btn-ghost"
            disabled={remove.isPending}
            onClick={() => setAsking(true)}
          >
            삭제
          </button>
        </div>
      </Panel>
      {asking && (
        <ConfirmDialog
          title={`쿠폰 #${item.couponId}을 삭제할까요?`}
          body="삭제하면 되돌릴 수 없고 목록과 발급에서 빠져요. 한 장이라도 발급했으면 삭제되지 않아요."
          confirmLabel="쿠폰 삭제하기"
          busy={remove.isPending}
          onConfirm={() => {
            setAsking(false);
            remove.mutate();
          }}
          onCancel={() => setAsking(false)}
        />
      )}
    </main>
  );
}
