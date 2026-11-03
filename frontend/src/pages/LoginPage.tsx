import { useMutation } from '@tanstack/react-query';
import { FormEvent, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api, setToken } from '../api/client';
import { ErrorNotice, Title } from '../components/Common';
import { ROLE_LABEL } from '../components/Layout';

export function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const next = params.get('next');
  const login = useMutation({
    mutationFn: (credentials: { email: string; password: string }) =>
      api<{ accessToken: string }>('/api/auth/login', { method: 'POST', body: credentials }),
    onSuccess: (result) => {
      setToken(result.accessToken);
      // 외부 주소로 보내지 않도록 앱 안의 경로만 따른다.
      navigate(next?.startsWith('/') && !next.startsWith('//') ? next : '/', { replace: true });
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    login.mutate({ email, password });
  };
  return (
    <main className="content narrow">
      <Title en="Sign in" ko="로그인" />
      <form className="stack" onSubmit={submit}>
        {login.isError && <ErrorNotice error={login.error} />}
        <div className="field">
          <label htmlFor="email">이메일</label>
          <input id="email" className="input" type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="password">비밀번호</label>
          <input id="password" className="input" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>
        <button className="btn btn-primary" disabled={login.isPending}>
          로그인
        </button>
        <Link to="/signup" className="btn btn-ghost">
          회원가입
        </Link>
      </form>
      {__DEV_ACCOUNTS__.length > 0 && (
        <section className="stack dev-login" aria-label="테스트 계정으로 로그인">
          <p className="muted">개발 서버 전용 · 테스트 계정으로 로그인</p>
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
    </main>
  );
}
