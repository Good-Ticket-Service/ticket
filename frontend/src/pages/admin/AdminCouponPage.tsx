import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, errorMessage, getToken } from '../../api/client';
import type { Coupon, CouponInput } from '../../api/manageTypes';
import { ErrorNotice, Loading } from '../../components/Common';
import { CouponPolicyFields } from '../../components/CouponPolicyFields';
import { CouponPolicyPreview } from '../../components/CouponPolicyPreview';
import { CouponActionDialog } from '../../components/CouponActionDialog';
import {
  couponStatus,
  issuedCount,
  ISSUE_STATUS_LABEL,
  toCouponForm,
  updateCouponBody,
} from '../../lib/adminCoupons';
import { couponTitle, couponConditions } from '../../lib/coupons';
import { formatDateTime } from '../../lib/format';
export function AdminCouponPage() {
  const { couponId } = useParams();
  if (!couponId || !/^[1-9]\d*$/.test(couponId) || !Number.isSafeInteger(Number(couponId)))
    return (
      <main className="content seller-page">
        <p className="notice">올바른 쿠폰 ID를 입력해 주세요.</p>
        <Link to="/admin/coupons">쿠폰 목록</Link>
      </main>
    );
  return <Detail key={couponId} id={couponId} />;
}
function Detail({ id }: { id: string }) {
  const key = ['admin-coupon', getToken(), id];
  const query = useQuery({ queryKey: key, queryFn: () => api<Coupon>(`/api/coupons/${id}`) });
  if (query.isPending)
    return (
      <main className="content seller-page">
        <Loading />
      </main>
    );
  if (!query.data)
    return (
      <main className="content seller-page">
        <ErrorNotice error={query.error} />
        <button className="btn btn-ghost" onClick={() => query.refetch()}>
          쿠폰 다시 조회
        </button>
      </main>
    );
  return (
    <Editor
      item={query.data}
      refetch={query.refetch}
      fetching={query.isFetching}
      queryError={query.isError ? query.error : null}
    />
  );
}
function Editor({
  item,
  refetch,
  fetching,
  queryError,
}: {
  item: Coupon;
  refetch: () => Promise<{ data?: Coupon }>;
  fetching: boolean;
  queryError: unknown;
}) {
  const client = useQueryClient(),
    navigate = useNavigate(),
    location = useLocation();
  const key = ['admin-coupon', getToken(), String(item.couponId)],
    path = `/api/coupons/${item.couponId}`;
  const [before, setBefore] = useState(item),
    [form, setForm] = useState(() => toCouponForm(item));
  const [asking, setAsking] = useState<'close' | 'delete' | null>(null),
    [now, setNow] = useState(Date.now),
    [saved, setSaved] = useState(false);
  const lock = useRef(false);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const status = couponStatus(item, now),
    issued = issuedCount(item);
  const returnPath = location.state?.couponReturn;
  const back =
    typeof returnPath === 'string' && returnPath.startsWith('/admin/coupons?')
      ? returnPath
      : `/admin/coupons?category=${item.category}&issueStatus=${status}`;
  const refresh = (result: Coupon) => {
    client.setQueryData(key, result);
    setBefore(result);
    setForm(toCouponForm(result));
    client.invalidateQueries({ queryKey: ['admin-coupons'] });
    client.invalidateQueries({ queryKey: ['coupons'] });
  };
  const update = useMutation({
    mutationFn: (body: Partial<CouponInput>) => api<Coupon>(path, { method: 'PATCH', body }),
    onSuccess: (result) => {
      refresh(result);
      setSaved(true);
    },
    onError: () => refetch(),
  });
  const action = useMutation({
    mutationFn: async (kind: 'close' | 'delete') => {
      const latest = await api<Coupon>(path);
      client.setQueryData(key, latest);
      if (kind === 'delete') {
        if (issuedCount(latest) > 0)
          throw new ApiError(409, 'COUPON_NOT_DELETABLE', '발급 이력이 있어 삭제할 수 없어요.');
        await api(path, { method: 'DELETE' });
        return { kind };
      }
      return { kind, result: await api<Coupon>(`${path}/close`, { method: 'POST' }) };
    },
    onSuccess: (result) => {
      setAsking(null);
      if (result.kind === 'delete') {
        client.invalidateQueries({ queryKey: ['admin-coupons'] });
        client.invalidateQueries({ queryKey: ['coupons'] });
        navigate(back, { replace: true });
      } else if (result.result) {
        refresh(result.result);
        setSaved(true);
      }
    },
    onError: () => refetch(),
    onSettled: () => {
      lock.current = false;
    },
  });
  let body: Partial<CouponInput> = {},
    validation: string | null = null;
  try {
    body = updateCouponBody(form, before, item, now);
  } catch (error) {
    validation = (error as Error).message;
  }
  const busy = update.isPending || action.isPending;
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!busy && !fetching && !queryError && !validation && Object.keys(body).length) {
      setSaved(false);
      update.mutate(body);
    }
  };
  const reload = async () => {
    const latest = await refetch();
    if (latest.data) {
      setBefore(latest.data);
      setForm(toCouponForm(latest.data));
      setSaved(false);
      update.reset();
    }
  };
  return (
    <main className="content seller-page admin-page stack">
      <Link className="seller-back" to={back}>
        ‹ 쿠폰 목록
      </Link>
      <header className="seller-page-heading">
        <div>
          <h1 className="section-marker">쿠폰 정책</h1>
          <p>
            쿠폰 ID {item.couponId} · {item.category === 'WELCOME' ? '웰컴' : '일반'} 쿠폰
          </p>
        </div>
        <button className="btn btn-ghost" disabled={busy || fetching} onClick={reload}>
          최신 정책 불러오기
        </button>
      </header>
      {queryError != null && <ErrorNotice error={queryError} />}
      <section className="panel stack admin-accent-panel">
        <div className="settlement-panel-heading">
          <div>
            <span className={`seller-state ${status === 'ISSUING' ? 'ready' : 'draft'}`}>
              {ISSUE_STATUS_LABEL[status]}
            </span>
            <h2 className="admin-coupon-name">{item.name}</h2>
            <p className="account-hint">
              {couponTitle(item)} · {couponConditions(item).join(' · ')}
            </p>
          </div>
          <div className="admin-coupon-quantity">
            <strong>{item.remainingQuantity.toLocaleString('ko-KR')}</strong>
            <span> / {item.totalQuantity.toLocaleString('ko-KR')}장 남음</span>
            <p>
              발급 {issued.toLocaleString('ko-KR')}장 · 발급 후 {item.validDays}일
            </p>
          </div>
        </div>
        <p className="account-hint">
          발급 기간 {formatDateTime(item.issueStartAt)} — {formatDateTime(item.issueEndAt)}
        </p>
      </section>
      <div className="admin-coupon-layout">
        <section className="panel stack">
          <h2 className="panel-title">정책 수정</h2>
          <p className="account-hint">
            {status === 'UPCOMING'
              ? '발급 전에는 정책을 수정할 수 있어요.'
              : status === 'ISSUING'
                ? '발급 시작 후 이름·종료 시각·총 수량만 수정할 수 있어요.'
                : '발급이 끝난 쿠폰은 이름만 수정할 수 있어요.'}{' '}
            총 수량과 삭제 가능 여부는 저장 시점에 서버가 다시 확인합니다.
          </p>
          <form className="stack" onSubmit={submit}>
            {update.isError && <ErrorNotice error={update.error} />}{' '}
            {saved && (
              <p className="account-success" role="status">
                쿠폰 정책을 반영했어요.
              </p>
            )}
            <CouponPolicyFields
              value={form}
              onChange={(value) => {
                setForm(value);
                setSaved(false);
              }}
              status={status}
              original={item}
              disabled={busy || fetching || !!queryError}
            />
            {validation && (
              <p className="notice" role="alert">
                {validation}
              </p>
            )}
            <button
              className="btn btn-primary"
              disabled={busy || fetching || !!queryError || !!validation || !Object.keys(body).length}
            >
              {update.isPending
                ? '저장 중'
                : Object.keys(body).length
                  ? '변경사항 저장'
                  : '변경한 항목이 없어요'}
            </button>
          </form>
        </section>
        <div className="stack">
          <CouponPolicyPreview value={form} />
          <section className="panel stack admin-coupon-actions">
            <h2 className="panel-title">발급 운영</h2>
            <p className="account-hint">
              발급 중단은 이미 받은 쿠폰에 영향을 주지 않습니다. 발급 이력이 있는 쿠폰은 삭제할 수 없어요.
            </p>
            <button
              className="btn btn-ghost"
              type="button"
              disabled={busy || fetching || status === 'ENDED'}
              onClick={() => {
                action.reset();
                setAsking('close');
              }}
            >
              발급 중단
            </button>
            <button
              className="btn btn-ghost"
              type="button"
              disabled={busy || fetching || issued > 0}
              onClick={() => {
                action.reset();
                setAsking('delete');
              }}
            >
              쿠폰 삭제
            </button>
            {issued > 0 && <p className="account-hint">발급 {issued}장 · 삭제 불가</p>}
          </section>
        </div>
      </div>
      {asking && (
        <CouponActionDialog
          kind={asking}
          name={item.name}
          busy={action.isPending}
          error={action.isError ? errorMessage(action.error) : null}
          onDismiss={() => {
            if (!action.isPending) setAsking(null);
          }}
          onConfirm={() => {
            if (lock.current) return;
            lock.current = true;
            action.mutate(asking);
          }}
        />
      )}
    </main>
  );
}
