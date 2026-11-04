import { useMutation } from '@tanstack/react-query';
import { FormEvent, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError, ifMatch } from '../../api/client';
import type { GradesResult, SeatInput, SeatsResult } from '../../api/manageTypes';
import { ErrorNotice, Title } from '../../components/Common';
import { Done, Field, Panel, VersionOverride } from '../../components/Form';
import { useLocalRecords } from '../../lib/localRecords';
import { VENUE_RECORDS, VenueRecord } from './AdminVenuesPage';

/** "1-3"은 1,2,3, "A-C"는 A,B,C, "가,나"는 그대로 나눈다. */
function expandRows(text: string): string[] {
  const range = /^\s*(\w)\s*-\s*(\w)\s*$/.exec(text);
  if (range) {
    const [from, to] = [range[1], range[2]];
    if (/\d/.test(from) && /\d/.test(to)) {
      return Array.from({ length: Math.max(0, Number(to) - Number(from) + 1) }, (_, i) => String(Number(from) + i));
    }
    const start = from.charCodeAt(0);
    return Array.from({ length: Math.max(0, to.charCodeAt(0) - start + 1) }, (_, i) => String.fromCharCode(start + i));
  }
  return text.split(',').map((row) => row.trim()).filter(Boolean);
}

export function AdminVenuePage() {
  const venueId = Number(useParams().venueId);
  const venues = useLocalRecords<VenueRecord>(VENUE_RECORDS);
  // 다른 곳에서 만든 공연장은 version과 등급을 모르므로 직접 입력받는다.
  const record = venues.find(venueId) ?? { id: venueId, name: `공연장 #${venueId}`, region: '', version: 0, grades: [], seatCount: 0 };
  const known = venues.find(venueId) !== undefined;
  const [version, setVersion] = useState('');
  const currentVersion = version === '' ? record.version : Number(version);
  const save = (next: Partial<VenueRecord>) => {
    venues.save({ ...record, ...next });
    setVersion('');
  };

  const [gradeNames, setGradeNames] = useState('VIP, R, S');
  const grades = useMutation({
    mutationFn: () =>
      api<GradesResult>(`/api/admin/venues/${venueId}/seat-grades`, {
        method: 'POST',
        headers: ifMatch(currentVersion),
        body: { grades: gradeNames.split(',').map((name) => ({ name: name.trim() })).filter((g) => g.name) },
      }),
    onSuccess: (result) => save({ version: result.venueVersion, grades: [...record.grades, ...result.grades] }),
  });

  const [seat, setSeat] = useState({ section: 'A', rows: '1-3', perRow: '10', gradeId: '' });
  const gradeId = Number(seat.gradeId || record.grades[0]?.id || 0);
  const seats: SeatInput[] = expandRows(seat.rows).flatMap((rowLabel) =>
    Array.from({ length: Number(seat.perRow) || 0 }, (_, i) => ({
      section: seat.section,
      rowLabel,
      seatNumber: i + 1,
      venueSeatGradeId: gradeId,
    })),
  );
  const addSeats = useMutation({
    mutationFn: () =>
      api<SeatsResult>(`/api/admin/venues/${venueId}/seats`, { method: 'POST', headers: ifMatch(currentVersion), body: { seats } }),
    onSuccess: (result) => save({ version: result.venueVersion, seatCount: record.seatCount + result.createdCount }),
  });

  const submitGrades = (event: FormEvent) => {
    event.preventDefault();
    grades.mutate();
  };
  const submitSeats = (event: FormEvent) => {
    event.preventDefault();
    addSeats.mutate();
  };
  return (
    <main className="content medium stack" style={{ gap: 24 }}>
      <Link to="/admin/venues" className="navlink" style={{ paddingLeft: 0 }}>
        ← 공연장
      </Link>
      <Title en="Venue" ko={record.name} />
      <Panel
        title={`공연장 ID ${venueId}`}
        note="판매자에게 공연장 ID와 아래 등급 ID를 알려 주세요. 공연이 이 공연장을 쓰기 시작하면 등급·좌석을 더 넣을 수 없어요."
      >
        <VersionOverride
          id="venue-version"
          open={!known || [grades.error, addSeats.error].some((e) => e instanceof ApiError && e.code === 'VERSION_CONFLICT')}
          current={known ? record.version : null}
          value={version}
          onChange={setVersion}
        />
        <div className="chips">
          {record.grades.length === 0 && <span className="muted">등록한 등급이 없어요.</span>}
          {record.grades.map((grade) => (
            <span key={grade.id} className="pill num">
              {grade.name} · 등급 ID {grade.id}
            </span>
          ))}
        </div>
      </Panel>
      <Panel title="등급 등록" note="쉼표로 여러 개를 한 번에 넣어요. 같은 공연장에 이름이 같은 등급은 넣을 수 없어요.">
        <form className="stack" onSubmit={submitGrades}>
          {grades.isError && <ErrorNotice error={grades.error} />}
          {grades.isSuccess && <Done>등급 {grades.data.grades.length}개를 넣었어요.</Done>}
          <Field id="grade-names" label="등급 이름">
            <input id="grade-names" className="input" required value={gradeNames} onChange={(e) => setGradeNames(e.target.value)} />
          </Field>
          <button className="btn btn-primary" disabled={grades.isPending}>
            등급 등록
          </button>
        </form>
      </Panel>
      <Panel title="좌석 일괄 등록" note={`구역 하나에 열 범위 × 열마다 좌석 수만큼 만들어요. 지금 설정이면 ${seats.length}석이에요.`}>
        <form className="stack" onSubmit={submitSeats}>
          {addSeats.isError && <ErrorNotice error={addSeats.error} />}
          {addSeats.isSuccess && <Done>좌석 {addSeats.data.createdCount}석을 만들었어요.</Done>}
          <div className="form-grid">
            <Field id="seat-section" label="구역">
              <input id="seat-section" className="input" required maxLength={20} value={seat.section} onChange={(e) => setSeat({ ...seat, section: e.target.value })} />
            </Field>
            <Field id="seat-rows" label="열" hint="1-3, A-C 또는 쉼표로 나열">
              <input id="seat-rows" className="input" required value={seat.rows} onChange={(e) => setSeat({ ...seat, rows: e.target.value })} />
            </Field>
            <Field id="seat-perRow" label="열마다 좌석 수">
              <input id="seat-perRow" className="input" type="number" min={1} required value={seat.perRow} onChange={(e) => setSeat({ ...seat, perRow: e.target.value })} />
            </Field>
            <Field id="seat-grade" label="등급">
              {record.grades.length > 0 ? (
                <select id="seat-grade" className="input" value={seat.gradeId || record.grades[0].id} onChange={(e) => setSeat({ ...seat, gradeId: e.target.value })}>
                  {record.grades.map((grade) => (
                    <option key={grade.id} value={grade.id}>
                      {grade.name} (ID {grade.id})
                    </option>
                  ))}
                </select>
              ) : (
                <input id="seat-grade" className="input" type="number" min={1} required placeholder="등급 ID" value={seat.gradeId} onChange={(e) => setSeat({ ...seat, gradeId: e.target.value })} />
              )}
            </Field>
          </div>
          <button className="btn btn-primary" disabled={addSeats.isPending || seats.length === 0 || !gradeId}>
            좌석 {seats.length}석 등록
          </button>
        </form>
      </Panel>
    </main>
  );
}
