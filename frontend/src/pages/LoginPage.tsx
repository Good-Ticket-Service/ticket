import { useMutation } from '@tanstack/react-query';
import { FormEvent, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api, setToken } from '../api/client';
import { ErrorNotice, Title } from '../components/Common';

export function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const next = params.get('next');
  const login = useMutation({
    mutationFn: () => api<{ accessToken: string }>('/api/auth/login', { method: 'POST', body: { email, password } }),
    onSuccess: (result) => {
      setToken(result.accessToken);
      // 외부 주소로 보내지 않도록 앱 안의 경로만 따른다.
      navigate(next?.startsWith('/') && !next.startsWith('//') ? next : '/', { replace: true });
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    login.mutate();
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
    </main>
  );
}
