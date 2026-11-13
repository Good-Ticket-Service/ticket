import { GlassVenueIcon } from '../../components/GlassVenueIcon';
import { useMutation } from '@tanstack/react-query';
import { type FormEvent, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError, ifMatch } from '../../api/client';
import type { GradesResult, SeatsResult } from '../../api/manageTypes';
import { ErrorNotice } from '../../components/Common';
import { VersionOverride } from '../../components/Form';
import { AdminVenueList } from '../../components/AdminVenueList';
import { useLocalRecords } from '../../lib/localRecords';
import { VENUE_RECORDS, seatDraft, venueVersion, type VenueRecord } from '../../lib/adminManagement';

export function AdminVenuePage() {
  const { venueId } = useParams();
  if (!venueId || !/^[1-9]\d*$/.test(venueId) || !Number.isSafeInteger(Number(venueId)))
    return (
      <main className="content seller-page">
        <p className="notice">올바른 공연장 ID를 입력해 주세요.</p>
        <Link to="/admin/venues">공연장 관리</Link>
      </main>
    );
  return <Venue key={venueId} id={Number(venueId)} />;
}
function Venue({ id }: { id: number }) {
  const venues = useLocalRecords<VenueRecord>(VENUE_RECORDS),
    stored = venues.find(id);
  const record: VenueRecord = stored ?? {
    id,
    name: `공연장 ID ${id}`,
    region: '',
    version: 0,
    versionKnown: false,
    grades: [],
    seatCount: null,
  };
  const [tab, setTab] = useState('info'),
    [version, setVersion] = useState(''),
    [gradeNames, setGradeNames] = useState('VIP, R, S'),
    [validation, setValidation] = useState<string | null>(null);
  const [seat, setSeat] = useState({ section: 'A', rows: '1-3', perRow: '10', gradeId: '' });
  const [copied, setCopied] = useState<'idle' | 'done' | 'failed'>('idle');
  const currentVersion = venueVersion(stored, version);
  const save = (next: Partial<VenueRecord>) => {
    venues.save({ ...record, ...next, versionKnown: true });
    setVersion('');
    setCopied('idle');
  };
  const grades = useMutation({
    mutationFn: () =>
      api<GradesResult>(`/api/admin/venues/${id}/seat-grades`, {
        method: 'POST',
        headers: ifMatch(currentVersion!),
        body: {
          grades: gradeNames
            .split(',')
            .map((name) => ({ name: name.trim() }))
            .filter((item) => item.name),
        },
      }),
    onSuccess: (result) =>
      save({ version: result.venueVersion, grades: [...record.grades, ...result.grades] }),
  });
  const gradeId = Number(seat.gradeId || record.grades[0]?.id || 0);
  const draft = useMemo(
    () => seatDraft(seat.section, seat.rows, seat.perRow, gradeId),
    [seat.section, seat.rows, seat.perRow, gradeId],
  );
  const seats = useMutation({
    mutationFn: () =>
      api<SeatsResult>(`/api/admin/venues/${id}/seats`, {
        method: 'POST',
        headers: ifMatch(currentVersion!),
        body: { seats: draft.seats },
      }),
    onSuccess: (result) =>
      save({
        version: result.venueVersion,
        seatCount: record.seatCount === null ? null : record.seatCount + result.createdCount,
      }),
  });
  const busy = grades.isPending || seats.isPending;
  const conflict = [grades.error, seats.error].some(
    (error) => error instanceof ApiError && error.code === 'VERSION_CONFLICT',
  );
  const versionReady = currentVersion !== null && (!conflict || version !== '');
  const handoff = `공연장: ${record.name} (공연장 ID ${id})\n등급 ID: ${record.grades.map((grade) => `${grade.name} ${grade.id}`).join(', ')}\n공연 등록에는 공연장 ID를, 가격 설정에는 판매 좌석이 쓰는 모든 등급 ID를 입력해 주세요.`;
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(handoff);
      setCopied('done');
    } catch {
      setCopied('failed');
    }
  };
  const submitGrades = (event: FormEvent) => {
    event.preventDefault();
    if (busy || !versionReady) return;
    const names = gradeNames
      .split(',')
      .map((value) => value.trim())
      .filter(Boolean);
    if (!names.length || names.some((name) => name.length > 20) || new Set(names).size !== names.length) {
      setValidation('등급 이름은 각각 1~20자로, 중복 없이 입력해 주세요.');
      return;
    }
    setValidation(null);
    grades.mutate();
  };
  return (
    <main className="content seller-page admin-page stack">
      <header className="seller-page-heading">
        <div>
          <h1 className="section-marker">공연장 관리</h1>
          <p>기록된 정보와 현재 version을 확인하고 등급·좌석을 등록하세요.</p>
        </div>
        <Link className="btn btn-primary" to="/admin/venues">
          ＋ 공연장 등록
        </Link>
      </header>
      <div className="admin-venue-layout">
        <AdminVenueList records={venues.records} selected={id} />
        <section className="admin-venue-detail">
          <div className="admin-venue-title">
            <GlassVenueIcon />
            <div>
              <h2>{record.name}</h2>
              <p>
                {record.address ?? '주소 미확인'} · ID {id}
              </p>
            </div>
          </div>
          <nav className="settlement-tabs" aria-label="공연장 설정">
            <button type="button" aria-pressed={tab === 'info'} onClick={() => setTab('info')}>
              기본 정보
            </button>
            <button type="button" aria-pressed={tab === 'seats'} onClick={() => setTab('seats')}>
              등급·좌석
            </button>
          </nav>
          <div className="stack admin-detail-body">
            <VersionOverride
              id="venue-version"
              open={!stored || conflict}
              current={stored?.versionKnown === false ? null : (stored?.version ?? null)}
              value={version}
              onChange={setVersion}
            />
            {!versionReady && (
              <p className="account-hint">
                등록 작업 전에 현재 공연장 version을 입력해 주세요. 다른 관리자가 바꿨다면 최신 값을 확인해야
                합니다.
              </p>
            )}
            {tab === 'info' ? (
              <>
                <dl className="settlement-account-facts">
                  <div>
                    <dt>지역</dt>
                    <dd>{record.region || '미확인'}</dd>
                  </div>
                  <div>
                    <dt>기록된 좌석</dt>
                    <dd>{record.seatCount === null ? '전체 수 미확인' : `${record.seatCount}석`}</dd>
                  </div>
                  <div>
                    <dt>기록된 등급</dt>
                    <dd>
                      {record.grades.length
                        ? record.grades.map((grade) => `${grade.name} (ID ${grade.id})`).join(' / ')
                        : '등급 정보 없음'}
                    </dd>
                  </div>
                </dl>
                <p className="account-hint">
                  위 내용은 브라우저 기록입니다. 공연이 이 공연장을 사용하기 시작하면 등급과 좌석을 추가할 수
                  없습니다.
                </p>
                {record.grades.length > 0 && (
                  <>
                    <button type="button" className="btn btn-ghost" onClick={copy}>
                      {copied === 'done' ? '전달 정보 복사됨' : '판매자 전달 정보 복사'}
                    </button>
                    {copied === 'failed' && (
                      <textarea className="input" aria-label="판매자 전달 정보" readOnly value={handoff} />
                    )}
                  </>
                )}
              </>
            ) : (
              <>
                <section className="stack">
                  <h3 className="panel-title">좌석 등급 등록</h3>
                  <p className="account-hint">
                    등급 가격은 판매자가 공연별로 설정합니다. 이름은 쉼표로 구분해 주세요.
                  </p>
                  <div className="chips">
                    {record.grades.map((grade) => (
                      <span className="seller-state ready" key={grade.id}>
                        {grade.name} · ID {grade.id}
                      </span>
                    ))}
                  </div>
                  <form className="stack" onSubmit={submitGrades}>
                    <fieldset className="seller-fieldset stack" disabled={busy || !versionReady}>
                      {grades.isError && <ErrorNotice error={grades.error} />}
                      {validation && (
                        <p className="notice" role="alert">
                          {validation}
                        </p>
                      )}
                      {grades.isSuccess && (
                        <p role="status" className="account-success">
                          등급 {grades.data.grades.length}개를 등록했어요.
                        </p>
                      )}
                      <div className="field">
                        <label htmlFor="grade-names">등급 이름</label>
                        <input
                          id="grade-names"
                          className="input"
                          required
                          value={gradeNames}
                          onChange={(event) => setGradeNames(event.target.value)}
                        />
                      </div>
                      <button className="btn btn-primary">등급 등록</button>
                    </fieldset>
                  </form>
                </section>
                <section className="stack admin-seat-section">
                  <h3 className="panel-title">좌석 일괄 등록</h3>
                  <p className="account-hint">
                    한 구역의 열 × 좌석 수를 등록합니다. 요청당 최대 5,000석입니다.
                  </p>
                  <form
                    className="stack"
                    onSubmit={(event) => {
                      event.preventDefault();
                      if (!busy && versionReady && !draft.error) seats.mutate();
                    }}
                  >
                    <fieldset className="seller-fieldset stack" disabled={busy || !versionReady}>
                      {seats.isError && <ErrorNotice error={seats.error} />}
                      {seats.isSuccess && (
                        <p className="account-success" role="status">
                          좌석 {seats.data.createdCount}석을 등록했어요.
                        </p>
                      )}
                      <div className="form-grid">
                        <div className="field">
                          <label htmlFor="seat-section">구역</label>
                          <input
                            id="seat-section"
                            className="input"
                            required
                            maxLength={20}
                            value={seat.section}
                            onChange={(e) => setSeat({ ...seat, section: e.target.value })}
                          />
                        </div>
                        <div className="field">
                          <label htmlFor="seat-rows">열</label>
                          <input
                            id="seat-rows"
                            className="input"
                            required
                            placeholder="1-3, A-C 또는 가,나,다"
                            value={seat.rows}
                            onChange={(e) => setSeat({ ...seat, rows: e.target.value })}
                          />
                        </div>
                        <div className="field">
                          <label htmlFor="seat-perRow">열마다 좌석 수</label>
                          <input
                            id="seat-perRow"
                            className="input"
                            type="number"
                            min={1}
                            max={5000}
                            required
                            value={seat.perRow}
                            onChange={(e) => setSeat({ ...seat, perRow: e.target.value })}
                          />
                        </div>
                        <div className="field">
                          <label htmlFor="seat-grade">등급 ID</label>
                          {record.grades.length ? (
                            <select
                              id="seat-grade"
                              className="input"
                              value={seat.gradeId || record.grades[0].id}
                              onChange={(e) => setSeat({ ...seat, gradeId: e.target.value })}
                            >
                              {record.grades.map((grade) => (
                                <option key={grade.id} value={grade.id}>
                                  {grade.name} · ID {grade.id}
                                </option>
                              ))}
                            </select>
                          ) : (
                            <input
                              id="seat-grade"
                              className="input"
                              type="number"
                              min={1}
                              required
                              value={seat.gradeId}
                              onChange={(e) => setSeat({ ...seat, gradeId: e.target.value })}
                            />
                          )}
                        </div>
                      </div>
                      {draft.error ? (
                        <p className="account-hint">{draft.error}</p>
                      ) : (
                        <div className="admin-seat-preview" aria-label="등록 예정 좌석 미리보기">
                          <p>
                            {seat.section}구역 · {draft.rows.length}열 · 총 {draft.total}석 등록 예정
                          </p>
                          {draft.rows.slice(0, 3).map((row) => (
                            <div key={row}>
                              <span>{row}열</span>
                              {Array.from({ length: Math.min(Number(seat.perRow), 12) }, (_, i) => (
                                <i key={i} aria-hidden="true" />
                              ))}
                            </div>
                          ))}
                          <small>처음 3열·열당 12석까지 표시한 등록 미리보기입니다.</small>
                        </div>
                      )}
                      <button className="btn btn-primary" disabled={!!draft.error}>
                        좌석 {draft.total}석 등록
                      </button>
                    </fieldset>
                  </form>
                </section>
              </>
            )}
          </div>
        </section>
      </div>
    </main>
  );
}
