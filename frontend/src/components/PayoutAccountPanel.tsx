import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';
import { apiWithEtag, ApiError, getToken, ifMatch } from '../api/client';
import type { PayoutAccount } from '../api/manageTypes';
import { ErrorNotice, Loading } from './Common';
import { formatDateTime } from '../lib/format';

export function PayoutAccountPanel() {
  const client = useQueryClient(),
    token = getToken();
  const key = ['payout-account', token];
  const account = useQuery({
    queryKey: key,
    retry: false,
    queryFn: async () => {
      try {
        return await apiWithEtag<PayoutAccount>('/api/seller/payout-account');
      } catch (error) {
        if (error instanceof ApiError && error.code === 'PAYOUT_ACCOUNT_NOT_FOUND') return null;
        throw error;
      }
    },
  });
  const [editing, setEditing] = useState(false),
    [saved, setSaved] = useState(false),
    [reviewed, setReviewed] = useState(false);
  const [form, setForm] = useState({ bankCode: '', accountNumber: '', holderName: '' });
  const [expectedVersion, setExpectedVersion] = useState<string | number | null>(null);
  const save = useMutation({
    gcTime: 0,
    mutationFn: (values: typeof form) =>
      apiWithEtag<PayoutAccount>('/api/seller/payout-account', {
        method: 'PUT',
        headers: expectedVersion !== null ? ifMatch(expectedVersion) : {},
        body: values,
      }),
    onSuccess: (result) => {
      client.setQueryData(key, result);
      setForm({ bankCode: '', accountNumber: '', holderName: '' });
      setEditing(false);
      setSaved(true);
      save.reset();
    },
    onError: (error) => {
      if (error instanceof ApiError && ['VERSION_CONFLICT', 'PRECONDITION_REQUIRED'].includes(error.code)) {
        setReviewed(true);
        account.refetch();
      }
    },
  });
  const current = account.data?.data;
  const clear = () => {
    setForm({ bankCode: '', accountNumber: '', holderName: '' });
    setEditing(false);
    save.reset();
    setReviewed(false);
  };
  const begin = () => {
    clear();
    setSaved(false);
    setForm({ bankCode: current?.bankCode ?? '', accountNumber: '', holderName: '' });
    setExpectedVersion(current ? (account.data!.etag ?? current.version) : null);
    setEditing(true);
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (account.isSuccess && !account.isFetching && !save.isPending && !reviewed) save.mutate({ ...form });
  };
  const bind = (name: keyof typeof form) => ({
    id: `account-${name}`,
    className: 'input',
    required: true,
    value: form[name],
    onChange: (e: { target: { value: string } }) => setForm({ ...form, [name]: e.target.value }),
  });
  return (
    <section className="panel stack settlement-account">
      <div className="settlement-panel-heading">
        <div>
          <h2 className="panel-title">지급 계좌</h2>
          <p className="account-hint">등록한 계좌는 마스킹된 정보로 표시합니다.</p>
        </div>
        {!editing && account.isSuccess && (
          <button type="button" className="btn btn-ghost" onClick={begin}>
            {current ? '계좌 변경' : '계좌 등록'}
          </button>
        )}
      </div>
      {account.isPending && <Loading />}
      {account.isError && (
        <div>
          <ErrorNotice error={account.error} />
          <button type="button" className="chip" onClick={() => account.refetch()}>
            계좌 다시 조회
          </button>
        </div>
      )}
      {saved && (
        <p className="account-success" role="status">
          지급 계좌를 저장했어요.
        </p>
      )}
      {current ? (
        <dl className="settlement-account-facts">
          <div>
            <dt>은행 코드</dt>
            <dd>{current.bankCode}</dd>
          </div>
          <div>
            <dt>계좌번호</dt>
            <dd>{current.accountMasked}</dd>
          </div>
          <div>
            <dt>예금주</dt>
            <dd>{current.holderMasked}</dd>
          </div>
          <div>
            <dt>최근 변경</dt>
            <dd>{formatDateTime(current.updatedAt)}</dd>
          </div>
        </dl>
      ) : (
        account.isSuccess && <p className="seller-empty">등록된 지급 계좌가 없어요.</p>
      )}
      {editing && (
        <form className="stack" onSubmit={submit}>
          <fieldset
            className="seller-fieldset stack"
            disabled={save.isPending}
          >
            {save.isError && <ErrorNotice error={save.error} />}
            {reviewed && (
              <div className="settlement-warning">
                <p>계좌 상태가 변경됐어요. 위의 최신 정보를 확인한 뒤 다시 저장해 주세요.</p>
                <button
                  type="button"
                  className="chip"
                  disabled={!account.isSuccess || account.isFetching}
                  onClick={() => {
                    setExpectedVersion(
                      account.data ? (account.data.etag ?? account.data.data.version) : null,
                    );
                    setReviewed(false);
                    save.reset();
                  }}
                >
                  최신 정보 확인했어요
                </button>
              </div>
            )}
            <div className="form-grid">
              <div className="field">
                <label htmlFor="account-bankCode">은행 코드</label>
                <input
                  {...bind('bankCode')}
                  pattern="\d{3}"
                  minLength={3}
                  maxLength={3}
                  inputMode="numeric"
                  placeholder="숫자 3자리"
                  autoComplete="off"
                />
              </div>
              <div className="field">
                <label htmlFor="account-accountNumber">계좌번호</label>
                <input
                  {...bind('accountNumber')}
                  pattern="[0-9\-]{6,30}"
                  minLength={6}
                  maxLength={30}
                  inputMode="numeric"
                  autoComplete="off"
                  placeholder="숫자 또는 하이픈, 6~30자"
                />
              </div>
              <div className="field">
                <label htmlFor="account-holderName">예금주</label>
                <input {...bind('holderName')} maxLength={50} pattern=".*\S.*" autoComplete="off" />
              </div>
            </div>
            <p className="account-hint">계좌번호와 예금주는 저장 성공 또는 입력 취소 시 화면에서 지웁니다.</p>
            <div className="profile-form-actions">
              <button type="button" className="btn btn-ghost" onClick={clear}>
                입력 취소
              </button>
              <button className="btn btn-primary" disabled={reviewed || !account.isSuccess || account.isFetching}>
                {save.isPending ? '저장 중' : current ? '변경 계좌 저장' : '계좌 등록하기'}
              </button>
            </div>
          </fieldset>
        </form>
      )}
    </section>
  );
}
