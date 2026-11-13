import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';
import { api, getToken } from '../api/client';
import type { User } from '../api/types';
import { ErrorNotice, Loading } from '../components/Common';
import { AccountNav } from '../components/AccountNav';
import { ROLE_LABEL, useMe } from '../components/Layout';
import { PHONE_PATTERN, profileChanges } from '../lib/account';
import { formatDateTime } from '../lib/format';

export function MyPage() {
  const me = useMe();
  if (me.isPending)
    return (
      <main className="content">
        <Loading />
      </main>
    );
  if (!me.data)
    return (
      <main className="content">
        <ErrorNotice error={me.error} />
        <button className="btn btn-ghost" onClick={() => me.refetch()}>
          다시 불러오기
        </button>
      </main>
    );
  return <Profile key={me.data.userId} user={me.data} />;
}
function Profile({ user }: { user: User }) {
  const client = useQueryClient();
  const token = getToken();
  const [editing, setEditing] = useState(false);
  const [saved, setSaved] = useState(false);
  const [values, setValues] = useState({ name: user.name, phone: user.phone });
  const update = useMutation({
    mutationFn: (changes: { name?: string; phone?: string }) =>
      api<User>('/api/users/me', { method: 'PATCH', body: changes }),
    onSuccess: (result) => {
      client.setQueryData(['me', token], result);
      setEditing(false);
      setSaved(true);
    },
  });
  const changes = profileChanges(user, values);
  const start = () => {
    setValues({ name: user.name, phone: user.phone });
    setSaved(false);
    update.reset();
    setEditing(true);
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (changes && !update.isPending) update.mutate(changes);
  };
  return (
    <main className="content my-bookings-content">
      <div className="my-bookings-layout">
        <AccountNav role={user.role} />
        <div className="profile-main">
          <header className="profile-heading">
            <h1 className="section-marker">내 정보</h1>
            <span>{ROLE_LABEL[user.role]}</span>
          </header>
          <section className="profile-panel">
            <div className="profile-summary">
              <span className="profile-avatar" aria-hidden="true">
                <svg viewBox="0 0 32 32" fill="none" stroke="currentColor" strokeWidth="1.5">
                  <circle cx="16" cy="10" r="5" />
                  <path d="M6 28v-3a10 10 0 0 1 20 0v3" />
                </svg>
              </span>
              <div>
                <h2>{user.name}</h2>
                <p>{user.email}</p>
              </div>
              {!editing && (
                <button className="btn btn-ghost" type="button" onClick={start}>
                  정보 수정
                </button>
              )}
            </div>
            {saved && (
              <p className="account-success" role="status">
                회원 정보를 저장했어요.
              </p>
            )}
            {editing ? (
              <form className="stack profile-form" onSubmit={submit}>
                <h3>회원 정보 수정</h3>
                <p className="account-hint">이름과 휴대폰 번호를 변경할 수 있어요.</p>
                {update.isError && <ErrorNotice error={update.error} />}
                <div className="field">
                  <label htmlFor="profile-name">이름</label>
                  <input
                    autoFocus
                    id="profile-name"
                    className="input"
                    autoComplete="name"
                    required
                    maxLength={50}
                    pattern=".*\S.*"
                    disabled={update.isPending}
                    value={values.name}
                    onChange={(e) => setValues({ ...values, name: e.target.value })}
                  />
                </div>
                <div className="field">
                  <label htmlFor="profile-phone">휴대폰 번호</label>
                  <input
                    id="profile-phone"
                    className="input"
                    type="tel"
                    autoComplete="tel"
                    required
                    pattern={PHONE_PATTERN}
                    disabled={update.isPending}
                    value={values.phone}
                    aria-describedby="profile-phone-hint"
                    onChange={(e) => setValues({ ...values, phone: e.target.value })}
                  />
                  <p id="profile-phone-hint" className="account-hint">
                    010-1234-5678 형식으로 입력해 주세요.
                  </p>
                </div>
                <div className="profile-form-actions">
                  <button
                    type="button"
                    className="btn btn-ghost"
                    disabled={update.isPending}
                    onClick={() => {
                      setEditing(false);
                      update.reset();
                    }}
                  >
                    돌아가기
                  </button>
                  <button className="btn btn-primary" disabled={!changes || update.isPending}>
                    {update.isPending ? '저장 중' : '변경사항 저장'}
                  </button>
                </div>
              </form>
            ) : (
              <dl className="profile-facts">
                <div>
                  <dt>이름</dt>
                  <dd>{user.name}</dd>
                </div>
                <div>
                  <dt>이메일</dt>
                  <dd>{user.email}</dd>
                </div>
                <div>
                  <dt>휴대폰 번호</dt>
                  <dd>{user.phone}</dd>
                </div>
                <div>
                  <dt>가입일</dt>
                  <dd>{formatDateTime(user.createdAt)}</dd>
                </div>
              </dl>
            )}
          </section>
          <p className="profile-meta">
            회원 번호 {user.userId} · 최근 정보 변경 {formatDateTime(user.updatedAt)}
          </p>
        </div>
      </div>
    </main>
  );
}
