import { useMutation } from '@tanstack/react-query';
import { FormEvent, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import type { VenueResult } from '../../api/manageTypes';
import { ErrorNotice, Title } from '../../components/Common';
import { Field, Panel } from '../../components/Form';
import { useLocalRecords } from '../../lib/localRecords';

/** 공연장 조회 API가 없어, 만든 공연장의 version과 등급을 이 브라우저에 기억한다. */
export interface VenueRecord {
  id: number;
  name: string;
  region: string;
  version: number;
  grades: { id: number; name: string }[];
  seatCount: number;
}

export const VENUE_RECORDS = 'ticket.admin.venues';

export function AdminVenuesPage() {
  const navigate = useNavigate();
  const venues = useLocalRecords<VenueRecord>(VENUE_RECORDS);
  const [form, setForm] = useState({ name: '', address: '', region: '' });
  const [openId, setOpenId] = useState('');
  const create = useMutation({
    mutationFn: () => api<VenueResult>('/api/admin/venues', { method: 'POST', body: form }),
    onSuccess: (venue) => {
      venues.save({ id: venue.id, name: venue.name, region: venue.region, version: venue.version, grades: [], seatCount: 0 });
      navigate(`/admin/venues/${venue.id}`);
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    create.mutate();
  };
  const bind = (name: keyof typeof form, max: number) => ({
    id: `venue-${name}`,
    className: 'input',
    required: true,
    maxLength: max,
    value: form[name],
    onChange: (e: { target: { value: string } }) => setForm({ ...form, [name]: e.target.value }),
  });
  return (
    <main className="content medium stack" style={{ gap: 24 }}>
      <Title en="Venues" ko="공연장" />
      <Panel title="공연장 만들기" note="만든 뒤 등급과 좌석을 이어서 등록해요. 판매자는 공연을 만들 때 공연장 ID와 등급 ID를 입력해요.">
        <form className="stack" onSubmit={submit}>
          {create.isError && <ErrorNotice error={create.error} />}
          <div className="form-grid">
            <Field id="venue-name" label="이름">
              <input {...bind('name', 100)} />
            </Field>
            <Field id="venue-address" label="주소">
              <input {...bind('address', 255)} />
            </Field>
            <Field id="venue-region" label="지역" hint="예: 서울">
              <input {...bind('region', 20)} />
            </Field>
          </div>
          <button className="btn btn-primary" disabled={create.isPending}>
            공연장 만들기
          </button>
        </form>
      </Panel>
      <Panel title="이 브라우저에서 만든 공연장" note="공연장 목록 API가 없어 여기서 만든 것만 보여요.">
        {venues.records.length === 0 && <p className="muted">아직 없어요.</p>}
        {venues.records.map((venue) => (
          <Link key={venue.id} to={`/admin/venues/${venue.id}`} className="item">
            <span>
              <span className="name">{venue.name}</span>
              <br />
              <span className="sub num">
                ID {venue.id} · {venue.region} · 등급 {venue.grades.length}개 · 좌석 {venue.seatCount}석
              </span>
            </span>
            <span className="pill num">v{venue.version}</span>
          </Link>
        ))}
        <form
          className="actions"
          onSubmit={(event) => {
            event.preventDefault();
            if (openId) navigate(`/admin/venues/${openId}`);
          }}
        >
          <input
            className="input"
            style={{ flex: '1 1 160px', width: 'auto' }}
            type="number"
            min={1}
            placeholder="다른 곳에서 만든 공연장 ID"
            aria-label="공연장 ID"
            value={openId}
            onChange={(e) => setOpenId(e.target.value)}
          />
          <button className="btn btn-ghost">ID로 열기</button>
        </form>
      </Panel>
    </main>
  );
}
