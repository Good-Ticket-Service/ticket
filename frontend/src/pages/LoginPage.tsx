import { useMutation } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api, setToken } from '../api/client';
import { ErrorNotice } from '../components/Common';
import { ROLE_LABEL } from '../components/Layout';
import { AuthShell } from '../components/AuthShell';
import { PasswordInput } from '../components/PasswordInput';
import { loginDestination } from '../lib/account';

export function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const login = useMutation({
    mutationFn: (credentials: { email: string; password: string }) =>
      api<{ accessToken: string }>('/api/auth/login', { method: 'POST', body: credentials }),
    onSuccess: (result) => {
      setToken(result.accessToken);
      setPassword('');
      navigate(loginDestination(params.get('next')), { replace: true });
    },
  });
  const next = loginDestination(params.get('next'));
  const signupQuery = next === '/' ? '' : `?next=${encodeURIComponent(next)}`;
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!login.isPending) login.mutate({ email, password });
  };
  return (
    <AuthShell>
      <header className="auth-heading">
        <h1 className="section-marker">로그인</h1>
        <p>로그인하고 예매를 이어가세요.</p>
      </header>
      {params.get('joined') === '1' && (
        <p role="status" className="account-success">
          가입이 완료됐어요. 로그인해 주세요.
        </p>
      )}
      <form className="stack auth-form" onSubmit={submit}>
        {login.isError && <ErrorNotice error={login.error} />}
        <div className="field">
          <label htmlFor="email">이메일</label>
          <input
            id="email"
            className="input"
            type="email"
            autoComplete="username"
            placeholder="이메일을 입력하세요"
            required
            maxLength={100}
            disabled={login.isPending}
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="password">비밀번호</label>
          <PasswordInput
            id="password"
            autoComplete="current-password"
            placeholder="비밀번호를 입력하세요"
            required
            disabled={login.isPending}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        <button className="btn btn-primary" disabled={login.isPending}>
          {login.isPending ? '로그인 중' : '로그인'}
        </button>
      </form>
      <div className="auth-join-links">
        <Link className="btn btn-ghost" to={`/signup${signupQuery}`}>
          회원가입
        </Link>
        <Link className="btn btn-ghost" to={`/seller/signup${signupQuery}`}>
          판매자 가입
        </Link>
      </div>
      {__DEV_ACCOUNTS__.length > 0 && (
        <section className="dev-login" aria-label="테스트 계정으로 로그인">
          <p>개발 서버 전용 · 테스트 계정</p>
          <div className="dev-login-buttons">
            {__DEV_ACCOUNTS__.map((account) => (
              <button
                key={account.role}
                type="button"
                className="btn btn-ghost"
                disabled={login.isPending}
                title={account.email}
                aria-label={`${ROLE_LABEL[account.role]} 테스트 계정으로 로그인`}
                onClick={() => login.mutate({ email: account.email, password: account.password })}
              >
                {ROLE_LABEL[account.role]}
              </button>
            ))}
          </div>
        </section>
      )}
    </AuthShell>
  );
}
