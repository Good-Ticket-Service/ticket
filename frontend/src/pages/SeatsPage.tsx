import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { Hold, Seat } from '../api/types';
import { ErrorNotice, Loading, Title } from '../components/Common';
import { won } from '../lib/format';
import { saveSeatLabel } from '../lib/seatLabels';

function nameOf(seat: Seat): string {
  return `${seat.gradeName} · ${seat.section} ${seat.rowLabel}열 ${seat.seatNumber}번`;
}

interface Section {
  name: string;
  rows: { label: string; seats: Seat[] }[];
}

function layout(seats: Seat[]): Section[] {
  const sections = new Map<string, Map<string, Seat[]>>();
  seats.forEach((seat) => {
    const rows = sections.get(seat.section) ?? new Map<string, Seat[]>();
    rows.set(seat.rowLabel, [...(rows.get(seat.rowLabel) ?? []), seat]);
    sections.set(seat.section, rows);
  });
  const natural = (a: string, b: string) => a.localeCompare(b, 'ko', { numeric: true });
  return [...sections.entries()]
    .sort(([a], [b]) => natural(a, b))
    .map(([name, rows]) => ({
      name,
      rows: [...rows.entries()]
        .sort(([a], [b]) => natural(a, b))
        .map(([label, list]) => ({ label, seats: list.sort((a, b) => a.seatNumber - b.seatNumber) })),
    }));
}

export function SeatsPage() {
  const { performanceId, scheduleId } = useParams();
  const navigate = useNavigate();
  const client = useQueryClient();
  const [picked, setPicked] = useState<Seat | null>(null);
  const seats = useQuery({
    queryKey: ['seats', scheduleId],
    queryFn: () => api<{ seats: Seat[] }>(`/api/seats/schedules/${scheduleId}`),
  });
  const hold = useMutation({
    mutationFn: (seat: Seat) =>
      api<Hold>(`/api/seats/schedules/${scheduleId}/holds`, { method: 'POST', body: { scheduleSeatId: seat.scheduleSeatId } }),
    onSuccess: (result, seat) => {
      saveSeatLabel(result.reservationId, nameOf(seat));
      navigate(`/reservations/${result.reservationId}/checkout`);
    },
    onError: () => {
      // 다른 사람이 먼저 잡았을 수 있으니 지도를 새로 읽고 선택을 푼다.
      setPicked(null);
      client.invalidateQueries({ queryKey: ['seats', scheduleId] });
    },
  });
  const sections = useMemo(() => layout(seats.data?.seats ?? []), [seats.data]);
  const grades = useMemo(() => {
    const unique = new Map<number, Seat>();
    seats.data?.seats.forEach((seat) => {
      if (!unique.has(seat.venueSeatGradeId)) unique.set(seat.venueSeatGradeId, seat);
    });
    // 비싼 등급부터 진한 색을 준다. 네 번째 등급부터는 마지막 색을 함께 쓰고 범례의 글자로 구분한다.
    return [...unique.values()].sort((a, b) => (b.basePrice ?? 0) - (a.basePrice ?? 0));
  }, [seats.data]);
  const gradeIndex = (seat: Seat) =>
    Math.min(2, grades.findIndex((grade) => grade.venueSeatGradeId === seat.venueSeatGradeId));

  const selected = picked ? (
    <>
      <div>
        <div className="muted" style={{ fontSize: 13 }}>
          선택한 좌석
        </div>
        <div style={{ fontSize: 18, fontWeight: 700 }}>{nameOf(picked)}</div>
        <div className="total num">{won(picked.basePrice)}</div>
      </div>
      <button type="button" className="btn btn-primary" disabled={hold.isPending} onClick={() => hold.mutate(picked)}>
        이 좌석 잡고 결제하기
      </button>
    </>
  ) : (
    <p className="muted">좌석을 하나 골라 주세요.</p>
  );

  return (
    <main className="content">
      <Link to={`/performances/${performanceId}`} className="navlink" style={{ paddingLeft: 0 }}>
        ← 공연 상세
      </Link>
      <Title en="Your Seat" ko="좌석 선택" />
      {seats.isPending && <Loading />}
      {seats.isError && <ErrorNotice error={seats.error} />}
      {seats.data && (
        <div className="split">
          <section className="main">
            <div className="stage" aria-hidden="true">
              <svg viewBox="0 0 700 44" preserveAspectRatio="none" fill="none">
                <path d="M30 38 Q350 -12 670 38" stroke="#fff" strokeOpacity="0.55" strokeWidth="1.2" vectorEffect="non-scaling-stroke" />
              </svg>
              <span>무대</span>
            </div>
            <div className="seatmap">
              {sections.map((section) => (
                <div key={section.name} className="seat-section">
                  <h3>{section.name}</h3>
                  {section.rows.map((row) => (
                    <div key={row.label} className="seat-row">
                      <span className="row-label">{row.label}</span>
                      {row.seats.map((seat) => (
                        <button
                          key={seat.scheduleSeatId}
                          type="button"
                          className={`seat g${gradeIndex(seat)} num`}
                          aria-label={`${nameOf(seat)}${seat.selectable ? '' : ', 선택 불가'}`}
                          aria-pressed={picked?.scheduleSeatId === seat.scheduleSeatId}
                          disabled={!seat.selectable}
                          onClick={() => setPicked(picked?.scheduleSeatId === seat.scheduleSeatId ? null : seat)}
                        >
                          {seat.seatNumber}
                        </button>
                      ))}
                    </div>
                  ))}
                </div>
              ))}
            </div>
          </section>
          <aside className="side panel stack">
            <div>
              <h2 style={{ fontSize: 16, fontWeight: 500, marginBottom: 12 }}>등급과 가격</h2>
              <div className="legend">
                {grades.map((grade, index) => (
                  <span key={grade.venueSeatGradeId} className="num">
                    <i className="swatch" style={{ background: `var(--grade-${Math.min(3, index + 1)})` }} />
                    {grade.gradeName} {won(grade.basePrice)}
                  </span>
                ))}
                <span>
                  <i className="swatch" style={{ background: '#fff' }} />
                  선택
                </span>
                <span>
                  <i className="swatch" style={{ borderColor: 'rgba(255,255,255,0.5)' }} />
                  선택 불가
                </span>
              </div>
            </div>
            {hold.isError && <ErrorNotice error={hold.error} />}
            <div className="desktop-only stack">{selected}</div>
          </aside>
          <div className="mobile-only seat-bar stack">{selected}</div>
        </div>
      )}
    </main>
  );
}
