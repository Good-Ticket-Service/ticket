import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FormEvent, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError, apiWithEtag, ifMatch } from '../../api/client';
import type { PayoutAccount, StatementStatus, StatementSummary } from '../../api/manageTypes';
import type { PageC } from '../../api/types';
import { ErrorNotice, Loading, Title } from '../../components/Common';
import { Done, Facts, Field, Panel } from '../../components/Form';
import { formatDate, formatDateTime, won } from '../../lib/format';

export const STATEMENT_STATUS_LABEL: Record<StatementStatus, string> = { ISSUED: '발행', NO_PAYOUT: '지급 없음', PAID: '지급 완료' };

export function SellerSettlementsPage() {
  const receivable = useQuery({
    queryKey: ['seller-receivable'],
    queryFn: () => api<{ receivable: number; oldestAt: string | null }>('/api/seller/settlements/receivable'),
  });
  return (
    <main className="content medium stack" style={{ gap: 24 }}>
      <Title en="Settlements" ko="정산" />
      <PayoutAccountPanel />
      <Panel title="미회수 차감" note="환불 등으로 아직 다음 정산에서 빼지 못한 금액이에요. 미지급액과는 별개예요.">
        {receivable.isPending && <Loading />}
        {receivable.isError && <ErrorNotice error={receivable.error} />}
        {receivable.data && (
          <Facts
            items={[
              ['미회수 금액', won(receivable.data.receivable)],
              ['가장 오래된 차감', receivable.data.oldestAt && formatDateTime(receivable.data.oldestAt)],
            ]}
          />
        )}
      </Panel>
      <Statements />
    </main>
  );
}

/** 등록 전에는 404라 If-Match 없이 등록하고, 등록 뒤에는 ETag를 If-Match로 보낸다. */
function PayoutAccountPanel() {
  const client = useQueryClient();
  const account = useQuery({
    queryKey: ['payout-account'],
    queryFn: async () => {
      try {
        return await apiWithEtag<PayoutAccount>('/api/seller/payout-account');
      } catch (error) {
        if (error instanceof ApiError && error.code === 'PAYOUT_ACCOUNT_NOT_FOUND') return null;
        throw error;
      }
    },
  });
  const [form, setForm] = useState({ bankCode: '', accountNumber: '', holderName: '' });
  const save = useMutation({
    mutationFn: () =>
      apiWithEtag<PayoutAccount>('/api/seller/payout-account', {
        method: 'PUT',
        headers: account.data?.etag ? ifMatch(account.data.etag) : {},
        body: form,
      }),
    onSuccess: (result) => {
      client.setQueryData(['payout-account'], result);
      setForm({ bankCode: '', accountNumber: '', holderName: '' });
    },
  });
  const set = (name: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [name]: e.target.value });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    save.mutate();
  };
  const current = account.data?.data;
  return (
    <Panel title="지급 계좌" note="계좌번호와 예금주 원문은 저장·응답하지 않고 가린 값만 보여 줘요.">
      {account.isPending && <Loading />}
      {account.isError && <ErrorNotice error={account.error} />}
      {account.isSuccess && !current && <p className="muted">등록한 계좌가 없어요.</p>}
      {current && (
        <Facts
          items={[
            ['은행 코드', current.bankCode],
            ['계좌', current.accountMasked],
            ['예금주', current.holderMasked],
            ['바꾼 시각', formatDateTime(current.updatedAt)],
          ]}
        />
      )}
      <form className="stack" onSubmit={submit}>
        {save.isError && <ErrorNotice error={save.error} />}
        {save.isSuccess && <Done>계좌를 저장했어요.</Done>}
        <div className="form-grid">
          <Field id="account-bankCode" label="은행 코드 (숫자 3자리)">
            <input id="account-bankCode" className="input" required pattern="\d{3}" inputMode="numeric" value={form.bankCode} onChange={set('bankCode')} />
          </Field>
          <Field id="account-accountNumber" label="계좌번호 (숫자·-, 6~30자)">
            <input id="account-accountNumber" className="input" required pattern="[0-9-]{6,30}" value={form.accountNumber} onChange={set('accountNumber')} />
          </Field>
          <Field id="account-holderName" label="예금주">
            <input id="account-holderName" className="input" required maxLength={50} value={form.holderName} onChange={set('holderName')} />
          </Field>
        </div>
        <button className="btn btn-primary" disabled={save.isPending || account.isPending}>
          {current ? '계좌 바꾸기' : '계좌 등록'}
        </button>
      </form>
    </Panel>
  );
}

function Statements() {
  const [filter, setFilter] = useState({ from: '', to: '', status: '' });
  const [page, setPage] = useState(0);
  const params = new URLSearchParams({ page: String(page), size: '10' });
  Object.entries(filter).forEach(([key, value]) => value && params.set(key, value));
  const list = useQuery({
    queryKey: ['seller-statements', params.toString()],
    queryFn: () => api<PageC<StatementSummary>>(`/api/seller/settlements?${params}`),
  });
  const set = (name: keyof typeof filter) => (e: { target: { value: string } }) => {
    setFilter({ ...filter, [name]: e.target.value });
    setPage(0);
  };
  return (
    <Panel title="정산서" note="발행일 기준으로 찾아요. 최근 발행부터 보여요.">
      <div className="form-grid">
        <Field id="statement-from" label="발행일 부터">
          <input id="statement-from" className="input" type="date" value={filter.from} onChange={set('from')} />
        </Field>
        <Field id="statement-to" label="발행일 까지">
          <input id="statement-to" className="input" type="date" value={filter.to} onChange={set('to')} />
        </Field>
        <Field id="statement-status" label="상태">
          <select id="statement-status" className="input" value={filter.status} onChange={set('status')}>
            <option value="">전체</option>
            {Object.entries(STATEMENT_STATUS_LABEL).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </Field>
      </div>
      {list.isPending && <Loading />}
      {list.isError && <ErrorNotice error={list.error} />}
      {list.data?.content.length === 0 && <p className="muted">정산서가 없어요.</p>}
      {list.data?.content.map((item) => (
        <Link key={item.statementId} to={`/seller/settlements/${item.statementId}`} className="item">
          <span>
            <span className="name num">{won(item.payableAmount)}</span>
            <br />
            <span className="sub num">
              회차 #{item.scheduleId} · {item.sequence}차 · 발행 {formatDate(item.issuedAt)}
            </span>
          </span>
          <span className={item.status === 'PAID' ? 'pill on' : 'pill'}>{STATEMENT_STATUS_LABEL[item.status]}</span>
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
    </Panel>
  );
}
