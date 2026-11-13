import { useMutation } from '@tanstack/react-query';
import { type FormEvent, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import { ErrorNotice } from '../components/Common';
import { AuthShell } from '../components/AuthShell';
import { PasswordInput } from '../components/PasswordInput';
import { loginDestination, PASSWORD_PATTERN, PHONE_PATTERN } from '../lib/account';

export function SignupPage({ seller = false }: { seller?: boolean }) {
  const [form, setForm] = useState({ email: '', password: '', name: '', phone: '' });
  const [confirmation, setConfirmation] = useState('');
  const [mismatch, setMismatch] = useState(false);
  const confirmRef = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = loginDestination(params.get('next'));
  const suffix = next === '/' ? '' : `&next=${encodeURIComponent(next)}`;
  const signup = useMutation({
    mutationFn: () =>
      api(seller ? '/api/auth/seller/signup' : '/api/auth/signup', { method: 'POST', body: form }),
    onSuccess: () => navigate(`/login?joined=1${suffix}`, { replace: true }),
  });
  const bind = (key: keyof typeof form) => ({
    id: key,
    required: true,
    disabled: signup.isPending,
    value: form[key],
    onChange: (e: { target: { value: string } }) => {
      setForm({ ...form, [key]: e.target.value });
      setMismatch(false);
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (signup.isPending) return;
    if (form.password !== confirmation) {
      setMismatch(true);
      confirmRef.current?.focus();
      return;
    }
    signup.mutate();
  };
  const nextQuery = next === '/' ? '' : `?next=${encodeURIComponent(next)}`;
  return (
    <AuthShell>
      <header className="auth-heading">
        <h1 className="section-marker">{seller ? '판매자 가입' : '회원가입'}</h1>
        <p>
          {seller ? '공연을 등록하고 판매할 계정을 만드세요.' : '예매에 사용할 회원 정보를 입력해 주세요.'}
        </p>
      </header>
      <form className="stack auth-form" onSubmit={submit}>
        {signup.isError && <ErrorNotice error={signup.error} />}
        <div className="field">
          <label htmlFor="email">이메일</label>
          <input
            {...bind('email')}
            className="input"
            type="email"
            maxLength={100}
            autoComplete="username"
            placeholder="example@email.com"
          />
        </div>
        <div className="field">
          <label htmlFor="password">비밀번호</label>
          <PasswordInput
            {...bind('password')}
            autoComplete="new-password"
            minLength={8}
            maxLength={15}
            pattern={PASSWORD_PATTERN}
            aria-describedby="password-hint"
          />
          <p className="account-hint" id="password-hint">
            8~15자, 대문자·소문자·숫자·특수문자 각각 포함
          </p>
        </div>
        <div className="field">
          <label htmlFor="password-confirm">비밀번호 확인</label>
          <input
            ref={confirmRef}
            id="password-confirm"
            className="input"
            type="password"
            autoComplete="new-password"
            required
            maxLength={15}
            disabled={signup.isPending}
            value={confirmation}
            aria-invalid={mismatch || undefined}
            aria-describedby={mismatch ? 'password-mismatch' : undefined}
            onChange={(e) => {
              setConfirmation(e.target.value);
              setMismatch(false);
            }}
          />
          {mismatch && (
            <p className="account-error" id="password-mismatch" role="alert">
              비밀번호가 일치하지 않아요.
            </p>
          )}
        </div>
        <div className="field">
          <label htmlFor="name">이름</label>
          <input {...bind('name')} className="input" autoComplete="name" maxLength={50} pattern=".*\S.*" />
        </div>
        <div className="field">
          <label htmlFor="phone">휴대폰 번호</label>
          <input
            {...bind('phone')}
            className="input"
            type="tel"
            autoComplete="tel"
            pattern={PHONE_PATTERN}
            placeholder="010-1234-5678"
            aria-describedby="phone-hint"
          />
          <p id="phone-hint" className="account-hint">
            하이픈(-)을 포함해 입력해 주세요.
          </p>
        </div>
        <button className="btn btn-primary" disabled={signup.isPending}>
          {signup.isPending ? '가입 중' : seller ? '판매자로 가입하기' : '가입하기'}
        </button>
      </form>
      <p className="auth-login-link">
        이미 계정이 있나요? <Link to={`/login${nextQuery}`}>로그인</Link>
      </p>
      <Link className="auth-role-link" to={`${seller ? '/signup' : '/seller/signup'}${nextQuery}`}>
        {seller ? '일반 회원으로 가입하기' : '판매자 가입으로 전환'} ↗
      </Link>
    </AuthShell>
  );
}
