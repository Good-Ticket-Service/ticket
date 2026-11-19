import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, conditionalApi, getToken } from '../api/client';
import type { Hold, PerformanceDetail, Schedule, Seat, SeatLayout, SeatStatus } from '../api/types';
import { ErrorNotice, Loading, Poster } from '../components/Common';
import { clearEntryToken, entryToken } from '../lib/entryTokens';
import { formatDateTime, won } from '../lib/format';
import { saveSeatLabel } from '../lib/seatLabels';
import { mergeSeatStatus, seatName, seatSections } from '../lib/seatMap';

export function SeatsPage() {
  const { performanceId, scheduleId } = useParams();
  const navigate = useNavigate();
  const client = useQueryClient();
  const [pickedId, setPickedId] = useState<number | null>(null);
  const [zoom, setZoom] = useState(1);
  const [uncertain, setUncertain] = useState<Seat | null>(null);
  const valid = /^[1-9]\d*$/.test(scheduleId ?? '') && /^[1-9]\d*$/.test(performanceId ?? '');
  const auth = getToken();
  const layoutKey = ['seat-layout', scheduleId, auth];
  const seatKey = ['seats', scheduleId, auth];
  const headers = (): Record<string, string> => {
    const token = entryToken(scheduleId!);
    return token ? { 'X-Queue-Token': token } : {};
  };
  const backToQueue = (error: unknown) => {
    if (!(error instanceof ApiError && error.code === 'QUEUE_TOKEN_INVALID')) return false;
    clearEntryToken(scheduleId!);
    client.removeQueries({ queryKey: seatKey });
    setPickedId(null);
    navigate(`/performances/${performanceId}/queue?scheduleId=${scheduleId}`, {
      replace: true,
      state: { expired: true },
    });
    return true;
  };
  const performance = useQuery({
    queryKey: ['performance', performanceId],
    enabled: valid,
    queryFn: () => api<PerformanceDetail>(`/api/performances/${performanceId}`),
  });
  const schedule = useQuery({
    queryKey: ['schedule', scheduleId],
    enabled: valid,
    queryFn: () => api<Schedule>(`/api/schedules/${scheduleId}`),
  });
  const matches = schedule.data?.performanceId === Number(performanceId);
  const seats = useQuery({
    queryKey: seatKey,
    enabled: valid && matches,
    queryFn: async ({ signal }) => {
      const path = `/api/seats/schedules/${scheduleId}`;
      let layout = client.getQueryData<{ data: SeatLayout; etag: string | null }>(layoutKey);
      const load = async () => {
        layout = await conditionalApi<SeatLayout>(`${path}/layout`, layout, { headers: headers(), signal });
        client.setQueryData(layoutKey, layout);
        return layout;
      };
      if (!layout) await load();
      let status = await api<SeatStatus>(`${path}/status`, { headers: headers(), cache: 'no-store', signal });
      if (layout!.data.configVersion !== status.configVersion) {
        await load();
        status = await api<SeatStatus>(`${path}/status`, { headers: headers(), cache: 'no-store', signal });
      }
      return { seats: mergeSeatStatus(layout!.data, status), asOf: status.asOf };
    },
    refetchInterval: (query) => (query.state.status === 'error' ? false : 3000),
    refetchOnWindowFocus: true,
    retry: false,
  });
  useEffect(() => {
    if (seats.isError) backToQueue(seats.error);
  }, [seats.isError, seats.error]);
  const picked =
    seats.data?.seats.find((seat) => seat.scheduleSeatId === pickedId && seat.selectable) ?? null;
  useEffect(() => {
    if (pickedId !== null && seats.data && !picked) setPickedId(null);
  }, [pickedId, picked, seats.data]);
  const hold = useMutation({
    mutationFn: (seat: Seat) =>
      api<Hold>(`/api/seats/schedules/${scheduleId}/holds`, {
        method: 'POST',
        body: { scheduleSeatId: seat.scheduleSeatId },
        headers: headers(),
      }),
    onSuccess: (result, seat) => {
      setUncertain(null);
      saveSeatLabel(result.reservationId, seatName(seat));
      navigate(`/reservations/${result.reservationId}/checkout`);
    },
    onError: (error, seat) => {
      if (backToQueue(error)) return;
      setUncertain(error instanceof ApiError && (error.status === 0 || error.status >= 500) ? seat : null);
      setPickedId(null);
      client.invalidateQueries({ queryKey: seatKey });
    },
  });
  const sections = useMemo(() => seatSections(seats.data?.seats ?? []), [seats.data]);
  const grades = useMemo(() => {
    const unique = new Map<number, Seat>();
    seats.data?.seats.forEach((seat) => {
      if (!unique.has(seat.venueSeatGradeId)) unique.set(seat.venueSeatGradeId, seat);
    });
    return [...unique.values()].sort((a, b) => (b.basePrice ?? 0) - (a.basePrice ?? 0));
  }, [seats.data]);
  const gradeIndex = (seat: Seat) =>
    Math.min(
      2,
      Math.max(
        0,
        grades.findIndex((grade) => grade.venueSeatGradeId === seat.venueSeatGradeId),
      ),
    );
  if (!valid)
    return (
      <main className="content">
        <p className="notice">올바른 공연과 회차를 선택해 주세요.</p>
        <Link to="/">공연 목록</Link>
      </main>
    );
  return (
    <main className="content seat-content">
      <Link className="seat-back" to={`/performances/${performanceId}`}>
        ‹ 공연 상세
      </Link>
      <ol className="booking-steps" aria-label="예매 진행 단계">
        <li aria-current="step">
          <span>1</span>좌석 선택
        </li>
        <li>
          <span>2</span>주문·결제
        </li>
        <li>
          <span>3</span>예매 완료
        </li>
      </ol>
      <div className="seat-heading">
        <h1 className="section-marker">좌석 선택</h1>
        <p>1인 1매 · 좌석을 고른 뒤 다음 단계로 진행하세요.</p>
      </div>
      {schedule.isPending && <Loading />}
      {schedule.isError && <ErrorNotice error={schedule.error} />}
      {schedule.isSuccess && !matches && <p className="notice">이 공연에 속한 회차가 아니에요.</p>}
      {matches && (
        <div className="seat-selection-layout">
          <section className="seat-map-panel" aria-label="좌석 배치도">
            <div className="seat-map-toolbar">
              <div className="seat-grade-legend">
                {grades.map((grade, index) => (
                  <span key={grade.venueSeatGradeId}>
                    <i className={`seat-key g${Math.min(2, index)}`} />
                    {grade.gradeName}
                  </span>
                ))}
                <span>
                  <i className="seat-key selected" />
                  선택
                </span>
                <span>
                  <i className="seat-key unavailable" />
                  선택 불가
                </span>
              </div>
              <div className="seat-zoom">
                <button
                  type="button"
                  aria-label="좌석 축소"
                  disabled={zoom <= 1}
                  onClick={() => setZoom(Math.max(1, zoom - 0.25))}
                >
                  −
                </button>
                <button
                  type="button"
                  aria-label="좌석 확대"
                  disabled={zoom >= 1.5}
                  onClick={() => setZoom(Math.min(1.5, zoom + 0.25))}
                >
                  +
                </button>
              </div>
            </div>
            {seats.isPending && <Loading />}
            {seats.isError && (
              <div className="seat-map-error">
                <ErrorNotice error={seats.error} />
                <button type="button" className="btn btn-ghost" onClick={() => seats.refetch()}>
                  좌석 다시 불러오기
                </button>
              </div>
            )}
            {seats.data && (
              <>
                <div className="seatmap-scroll" style={{ '--seat-zoom': zoom } as React.CSSProperties}>
                  <div className="seat-stage">
                    STAGE<span>무대</span>
                  </div>
                  <div className="seat-zones">
                    {sections.map((section) => (
                      <section className="seat-zone" key={section.name} aria-label={`${section.name} 좌석`}>
                        <h2>{section.name}</h2>
                        {section.rows.map((row) => (
                          <div className="seat-map-row" key={row.label}>
                            <span className="seat-row-label">{row.label}</span>
                            {row.seats.map((seat) => (
                              <button
                                type="button"
                                key={seat.scheduleSeatId}
                                className={`map-seat g${gradeIndex(seat)}`}
                                aria-label={`${seatName(seat)}${seat.selectable ? '' : ', 선택 불가'}`}
                                aria-pressed={pickedId === seat.scheduleSeatId && seat.selectable}
                                disabled={!seat.selectable || hold.isPending || seats.isError || uncertain !== null}
                                onClick={() => {
                                  hold.reset();
                                  setPickedId(pickedId === seat.scheduleSeatId ? null : seat.scheduleSeatId);
                                }}
                              >
                                {seat.seatNumber}
                              </button>
                            ))}
                          </div>
                        ))}
                      </section>
                    ))}
                  </div>
                </div>
                {sections.length === 0 && <p className="home-empty">이 회차에 등록된 좌석이 없어요.</p>}
              </>
            )}
            <div className="seat-map-foot">
              <span>{seats.data ? `${formatDateTime(seats.data.asOf)} 기준` : ''}</span>
              <button
                type="button"
                className="booking-refresh"
                disabled={seats.isFetching || hold.isPending}
                onClick={() => seats.refetch()}
              >
                좌석 새로고침 ↻
              </button>
            </div>
            <p className="seat-map-note">
              배치도는 구역·열·좌석 번호 기준이에요. 실제 좌석 상태는 선점할 때 다시 확인해요.
            </p>
          </section>
          <aside className="seat-summary" aria-labelledby="seat-summary-title">
            <h2 id="seat-summary-title" className="section-marker">
              선택 내역
            </h2>
            <div className="seat-performance-summary">
              <div className="seat-summary-poster">
                <Poster src={performance.data?.posterUrl ?? null} />
              </div>
              <div>
                <strong>{performance.data?.title ?? '공연 정보 확인 중'}</strong>
                <p>{schedule.data && formatDateTime(schedule.data.startAt)}</p>
                <p>{performance.data?.venue.name}</p>
              </div>
            </div>
            {performance.isError && <ErrorNotice error={performance.error} />}
            <section className="seat-choice">
              <h3>
                선택 좌석 <span>({picked ? 1 : 0}석)</span>
              </h3>
              {picked ? (
                <div className="selected-seat-line">
                  <span>
                    <strong>{picked.gradeName}</strong>
                    {picked.section} {picked.rowLabel}열 {picked.seatNumber}번
                  </span>
                  <button
                    type="button"
                    aria-label="선택 좌석 해제"
                    disabled={hold.isPending}
                    onClick={() => setPickedId(null)}
                  >
                    ×
                  </button>
                </div>
              ) : (
                <p className="booking-hint">배치도에서 좌석을 하나 선택해 주세요.</p>
              )}
            </section>
            <dl className="seat-price-list">
              {grades.map((grade, index) => (
                <div key={grade.venueSeatGradeId}>
                  <dt>
                    <i className={`seat-key g${Math.min(2, index)}`} />
                    {grade.gradeName}
                  </dt>
                  <dd>{grade.basePrice === null ? '가격 미정' : won(grade.basePrice)}</dd>
                </div>
              ))}
            </dl>
            <dl className="seat-total">
              <dt>선택 좌석 금액</dt>
              <dd>{picked?.basePrice != null ? won(picked.basePrice) : '—'}</dd>
            </dl>
            {hold.isError && <ErrorNotice error={hold.error} />}
            {uncertain && (
              <div className="stack">
                <p className="booking-hint">
                  {seatName(uncertain)} 선점 결과를 확인하지 못했어요. 같은 좌석으로 다시 요청하면 기존 선점이 있는지도 확인해요.
                </p>
                <button
                  type="button"
                  className="btn btn-ghost"
                  disabled={hold.isPending}
                  onClick={() => hold.mutate(uncertain)}
                >
                  같은 좌석 선점 다시 확인
                </button>
              </div>
            )}
            <button
              type="button"
              className="btn btn-primary btn-block"
              disabled={!picked || picked.basePrice === null || hold.isPending || seats.isError}
              onClick={() => {
                if (picked) hold.mutate(picked);
              }}
            >
              {hold.isPending ? '좌석을 선점하고 있어요' : '좌석 선점 후 결제 →'}
            </button>
            <p className="seat-summary-note">
              선점이 완료되면 결제 화면으로 이동해요.
              <br />
              결제 가능한 남은 시간은 다음 화면에서 확인해 주세요.
            </p>
          </aside>
        </div>
      )}
    </main>
  );
}
