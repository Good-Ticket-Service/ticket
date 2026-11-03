import { useMutation } from '@tanstack/react-query';
import { FormEvent, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { ErrorNotice, Title } from '../components/Common';

/** 회원과 판매자는 같은 양식이고, 역할은 서버가 경로로 정한다. */
export function SignupPage({ seller = false }: { seller?: boolean }) {
  const [form, setForm] = useState({ email: '', password: '', name: '', phone: '' });
  const navigate = useNavigate();
  const signup = useMutation({
    mutationFn: () => api(seller ? '/api/auth/seller/signup' : '/api/auth/signup', { method: 'POST', body: form }),
    onSuccess: () => navigate('/login', { replace: true }),
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    signup.mutate();
  };
  const bind = (name: keyof typeof form) => ({
    id: name,
    className: 'input',
    required: true,
    value: form[name],
    onChange: (e: { target: { value: string } }) => setForm({ ...form, [name]: e.target.value }),
  });
  return (
    <main className="content narrow">
      <Title en={seller ? 'Seller' : 'Join'} ko={seller ? '판매자 가입' : '회원가입'} />
      <form className="stack" onSubmit={submit}>
        {signup.isError && <ErrorNotice error={signup.error} />}
        <div className="field">
          <label htmlFor="email">이메일</label>
          <input {...bind('email')} type="email" autoComplete="username" />
        </div>
        <div className="field">
          <label htmlFor="password">비밀번호 (8~15자, 대소문자·숫자·특수문자 포함)</label>
          <input {...bind('password')} type="password" autoComplete="new-password" minLength={8} maxLength={15} />
        </div>
        <div className="field">
          <label htmlFor="name">이름</label>
          <input {...bind('name')} autoComplete="name" />
        </div>
        <div className="field">
          <label htmlFor="phone">휴대폰 번호 (010-1234-5678)</label>
          <input {...bind('phone')} type="tel" autoComplete="tel" pattern="01[016789]-\d{3,4}-\d{4}" />
        </div>
        <button className="btn btn-primary" disabled={signup.isPending}>
          가입하기
        </button>
        <Link to={seller ? '/signup' : '/seller/signup'} className="btn btn-ghost">
          {seller ? '일반 회원으로 가입하기' : '공연을 판매하려면 판매자로 가입하기'}
        </Link>
      </form>
    </main>
  );
}
