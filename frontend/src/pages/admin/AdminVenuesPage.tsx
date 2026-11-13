import { useMutation } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import type { VenueResult } from '../../api/manageTypes';
import { ErrorNotice } from '../../components/Common';
import { AdminVenueList } from '../../components/AdminVenueList';
import { useLocalRecords } from '../../lib/localRecords';
import { VENUE_RECORDS, type VenueRecord } from '../../lib/adminManagement';

export function AdminVenuesPage() {
  const navigate = useNavigate(),
    venues = useLocalRecords<VenueRecord>(VENUE_RECORDS);
  const [form, setForm] = useState({ name: '', address: '', region: '' }),
    [openId, setOpenId] = useState('');
  const create = useMutation({
    mutationFn: () => api<VenueResult>('/api/admin/venues', { method: 'POST', body: form }),
    onSuccess: (venue) => {
      venues.save({ ...venue, grades: [], seatCount: 0, versionKnown: true });
      navigate(`/admin/venues/${venue.id}`);
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!create.isPending) create.mutate();
  };
  return (
    <main className="content seller-page admin-page stack">
      <header className="seller-page-heading">
        <div>
          <h1 className="section-marker">공연장 관리</h1>
          <p>공연장·좌석 등급·물리 좌석을 등록하고 관리하세요.</p>
        </div>
      </header>
      <div className="admin-venue-layout">
        <AdminVenueList records={venues.records} />
        <div className="stack">
          <section className="panel stack admin-accent-panel">
            <h2 className="panel-title">새 공연장 등록</h2>
            <p className="account-hint">
              등록 후 등급과 좌석을 설정합니다. 판매자에게 공연장·등급 ID를 전달할 수 있어요.
            </p>
            <form className="stack" onSubmit={submit}>
              <fieldset className="seller-fieldset stack" disabled={create.isPending}>
                {create.isError && <ErrorNotice error={create.error} />}
                {(
                  [
                    { key: 'name', label: '공연장명', max: 100 },
                    { key: 'region', label: '지역', max: 20 },
                    { key: 'address', label: '주소', max: 255 },
                  ] as const
                ).map((field) => (
                  <div className="field" key={field.key}>
                    <label htmlFor={`venue-${field.key}`}>{field.label}</label>
                    <input
                      id={`venue-${field.key}`}
                      className="input"
                      required
                      pattern=".*\S.*"
                      maxLength={field.max}
                      value={form[field.key]}
                      onChange={(e) => setForm({ ...form, [field.key]: e.target.value })}
                    />
                  </div>
                ))}
                <button className="btn btn-primary">{create.isPending ? '등록 중' : '공연장 등록'}</button>
              </fieldset>
            </form>
          </section>
          <section className="panel stack">
            <h2 className="panel-title">공연장 ID로 열기</h2>
            <form
              className="actions"
              onSubmit={(e) => {
                e.preventDefault();
                if (/^[1-9]\d*$/.test(openId) && Number.isSafeInteger(Number(openId)))
                  navigate(`/admin/venues/${openId}`);
              }}
            >
              <input
                className="input"
                style={{ flex: 1, minWidth: 0 }}
                aria-label="공연장 ID"
                type="number"
                min={1}
                max={Number.MAX_SAFE_INTEGER}
                required
                value={openId}
                onChange={(e) => setOpenId(e.target.value)}
              />
              <button className="btn btn-ghost">열기</button>
            </form>
          </section>
          <details className="seller-direct-open">
            <summary>브라우저 기록 정리</summary>
            <p className="account-hint">서버의 공연장은 삭제하지 않고 이 브라우저의 기록만 지웁니다.</p>
            {venues.records.map((venue) => (
              <div className="seller-record-cleanup" key={venue.id}>
                <span>{venue.name}</span>
                <button
                  type="button"
                  className="chip"
                  onClick={() => venues.remove(venue.id)}
                  aria-label={`${venue.name} 기록 지우기`}
                >
                  기록 지우기
                </button>
              </div>
            ))}
          </details>
        </div>
      </div>
    </main>
  );
}
