import { useMutation, useQuery } from '@tanstack/react-query';
import { FormEvent, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, ifMatch } from '../../api/client';
import type {
  AgeRating,
  Genre,
  GradePricesResult,
  PerformanceInput,
  SeatLayout,
  SellerPerformance,
  SellerSchedule,
} from '../../api/manageTypes';
import { ErrorNotice, Loading, Title } from '../../components/Common';
import { Done, Facts, Field, Panel, StepBadge, VersionOverride } from '../../components/Form';
import { seoulInputToOffset, toSeoulInput, won } from '../../lib/format';
import { GENRE_OPTIONS } from '../../lib/genres';
import { ageRatingLabel } from '../../lib/labels';
import { useLocalRecords } from '../../lib/localRecords';
import { PERFORMANCE_RECORDS, PerformanceRecord } from './SellerHomePage';

export function SellerPerformancePage() {
  const { performanceId } = useParams();
  return performanceId ? <ManagePerformance performanceId={Number(performanceId)} /> : <CreatePerformance />;
}

function CreatePerformance() {
  const navigate = useNavigate();
  const performances = useLocalRecords<PerformanceRecord>(PERFORMANCE_RECORDS);
  const [form, setForm] = useState({
    venueId: '',
    title: '',
    genre: 'MUSICAL' as Genre,
    ageRating: '' as AgeRating | '',
    ageGuidance: '',
    runningTime: '120',
    castInfo: '',
    description: '',
    posterUrl: '',
  });
  const create = useMutation({
    mutationFn: () =>
      api<SellerPerformance>('/api/seller/performances', {
        method: 'POST',
        body: {
          venueId: Number(form.venueId),
          title: form.title,
          genre: form.genre,
          ageRating: form.ageRating || null,
          ageGuidance: form.ageGuidance.trim() || null,
          runningTime: Number(form.runningTime),
          castInfo: form.castInfo || null,
          description: form.description || null,
          posterUrl: form.posterUrl || null,
        } satisfies PerformanceInput,
      }),
    onSuccess: (performance) => {
      performances.save({
        id: performance.id,
        title: performance.title,
        venueId: performance.venue.id,
        version: performance.version,
        publicationStatus: performance.publicationStatus,
        gradePrices: [],
        scheduleIds: [],
        hasIntro: Boolean(form.description && form.posterUrl),
      });
      navigate(`/seller/performances/${performance.id}`, { replace: true });
    },
  });
  const set = (name: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [name]: e.target.value });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    create.mutate();
  };
  return (
    <main className="content narrow stack" style={{ gap: 24 }}>
      <Link to="/seller" className="navlink" style={{ paddingLeft: 0 }}>
        ← 공연 관리
      </Link>
      <Title en="New Show" ko="공연 초안 등록" />
      <Panel
        title="기본 정보"
        note="공연장 ID는 관리자에게 받아요. 등록한 뒤에는 기본 정보를 고칠 수 없으니, 공개에 필요한 소개와 포스터 주소까지 지금 넣어요."
      >
        <form className="stack" onSubmit={submit}>
          {create.isError && <ErrorNotice error={create.error} />}
          <div className="form-grid">
            <Field id="perf-venueId" label="공연장 ID">
              <input id="perf-venueId" className="input" type="number" min={1} required value={form.venueId} onChange={set('venueId')} />
            </Field>
            <Field id="perf-runningTime" label="공연 시간(분)">
              <input id="perf-runningTime" className="input" type="number" min={1} required value={form.runningTime} onChange={set('runningTime')} />
            </Field>
            <Field id="perf-genre" label="장르">
              <select id="perf-genre" className="input" value={form.genre} onChange={set('genre')}>
                {GENRE_OPTIONS.map((genre) => (
                  <option key={genre.value} value={genre.value}>
                    {genre.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field id="perf-ageRating" label="관람 등급 (선택)">
              <select id="perf-ageRating" className="input" value={form.ageRating} onChange={set('ageRating')}>
                <option value="">선택 안 함</option>
                {(['ALL', '12', '15', '19'] satisfies AgeRating[]).map((rating) => (
                  <option key={rating} value={rating}>{ageRatingLabel(rating)}</option>
                ))}
              </select>
            </Field>
            <Field id="perf-ageGuidance" label="관람 연령 안내 (선택, 1~100자)">
              <input id="perf-ageGuidance" className="input" required={!form.ageRating} maxLength={100}
                placeholder="예: 만 8세 이상, 36개월 이상, 전체 관람가"
                value={form.ageGuidance} onChange={set('ageGuidance')} />
            </Field>
          </div>
          <p>관람 등급과 안내 중 하나 이상 입력해 주세요. 둘 다 입력하면 관객에게 안내 문구를 우선 표시합니다.</p>
          <Field id="perf-title" label="제목 (1~200자)">
            <input id="perf-title" className="input" required maxLength={200} value={form.title} onChange={set('title')} />
          </Field>
          <Field id="perf-castInfo" label="출연 (선택, 500자 이내)">
            <input id="perf-castInfo" className="input" maxLength={500} value={form.castInfo} onChange={set('castInfo')} />
          </Field>
          <Field id="perf-posterUrl" label="포스터 주소 (HTTPS)">
            <input id="perf-posterUrl" className="input" type="url" pattern="https://.*" required maxLength={500} value={form.posterUrl} onChange={set('posterUrl')} />
          </Field>
          <Field id="perf-description" label="소개">
            <textarea id="perf-description" className="input" required value={form.description} onChange={set('description')} />
          </Field>
          <button className="btn btn-primary" disabled={create.isPending}>
            초안 등록
          </button>
        </form>
      </Panel>
    </main>
  );
}

function ManagePerformance({ performanceId }: { performanceId: number }) {
  const performances = useLocalRecords<PerformanceRecord>(PERFORMANCE_RECORDS);
  const stored = performances.find(performanceId);
  // 다른 곳에서 만든 공연은 version·가격·회차를 모르므로 직접 입력받는다.
  const record: PerformanceRecord = stored ?? {
    id: performanceId,
    title: `공연 #${performanceId}`,
    venueId: 0,
    version: 0,
    publicationStatus: 'UNKNOWN',
    gradePrices: [],
    scheduleIds: [],
  };
  const [version, setVersion] = useState('');
  const currentVersion = version === '' ? record.version : Number(version);
  const save = (next: Partial<PerformanceRecord>) => {
    performances.save({ ...record, ...next });
    setVersion('');
  };
  const published = record.publicationStatus === 'PUBLISHED';

  const [prices, setPrices] = useState(() =>
    record.gradePrices.length > 0
      ? record.gradePrices.map((p) => ({ venueSeatGradeId: String(p.venueSeatGradeId), price: String(p.price) }))
      : [{ venueSeatGradeId: '', price: '' }],
  );
  const savePrices = useMutation({
    mutationFn: () =>
      api<GradePricesResult>(`/api/seller/performances/${performanceId}/grade-prices`, {
        method: 'PUT',
        headers: ifMatch(currentVersion),
        body: { gradePrices: prices.map((p) => ({ venueSeatGradeId: Number(p.venueSeatGradeId), price: Number(p.price) })) },
      }),
    onSuccess: (result) => save({ version: result.performanceVersion, gradePrices: result.gradePrices }),
  });

  const [schedule, setSchedule] = useState({
    startAt: toSeoulInput(undefined, 14 * 24 * 60),
    bookingOpenAt: toSeoulInput(undefined, 5),
    bookingCloseAt: toSeoulInput(undefined, 13 * 24 * 60),
  });
  const addSchedule = useMutation({
    mutationFn: () =>
      api<SellerSchedule>(`/api/seller/performances/${performanceId}/schedules`, {
        method: 'POST',
        body: {
          startAt: seoulInputToOffset(schedule.startAt),
          bookingOpenAt: seoulInputToOffset(schedule.bookingOpenAt),
          bookingCloseAt: seoulInputToOffset(schedule.bookingCloseAt),
        },
      }),
    // 회차 등록은 공연 version을 바꾸지 않는다. 직접 입력한 version이 있으면 그 값을 기억한다.
    onSuccess: (result) => save({ scheduleIds: [...record.scheduleIds, result.id], version: currentVersion }),
  });

  const publish = useMutation({
    mutationFn: () =>
      api<SellerPerformance>(`/api/seller/performances/${performanceId}/publication`, { method: 'POST', headers: ifMatch(currentVersion) }),
    onSuccess: (result) => save({ version: result.version, publicationStatus: result.publicationStatus }),
  });

  // 공개 조건 중 이 브라우저가 아는 것만 미리 막는다. 판매 좌석 수 같은 나머지는 서버가 판단한다.
  const missing = stored
    ? [
        record.hasIntro === false && '소개·포스터 주소(등록 뒤 고칠 수 없어 공연을 새로 등록해야 해요)',
        record.gradePrices.length === 0 && '1. 등급별 가격',
        record.scheduleIds.length === 0 && '2. 회차',
      ].filter((step): step is string => Boolean(step))
    : [];
  const conflict = [savePrices.error, publish.error].some((error) => error instanceof ApiError && error.code === 'VERSION_CONFLICT');

  const layout = useQuery({
    queryKey: ['seat-layout', performanceId],
    enabled: published,
    queryFn: () => api<SeatLayout>(`/api/performances/${performanceId}/seat-layout`),
  });
  const byGrade = new Map<string, { total: number; sellable: number; price: number | null }>();
  layout.data?.seats.forEach((seat) => {
    const entry = byGrade.get(seat.gradeName) ?? { total: 0, sellable: 0, price: seat.basePrice };
    entry.total += 1;
    if (seat.isSellable) entry.sellable += 1;
    byGrade.set(seat.gradeName, entry);
  });

  return (
    <main className="content medium stack" style={{ gap: 24 }}>
      <Link to="/seller" className="navlink" style={{ paddingLeft: 0 }}>
        ← 공연 관리
      </Link>
      <Title en="Show" ko={record.title} />
      <Panel
        title={`공연 ID ${performanceId}`}
        note={stored ? undefined : '이 브라우저에서 만든 공연이 아니에요. 가격 설정·공개 전에 아래에서 version을 입력해요.'}
      >
        <Facts
          items={[
            ['공개 상태', published ? '공개' : record.publicationStatus === 'UNKNOWN' ? '모름' : '초안'],
            ['공연장 ID', record.venueId || '모름'],
          ]}
        />
        <VersionOverride
          id="perf-version"
          open={!stored || conflict}
          current={stored ? record.version : null}
          value={version}
          onChange={setVersion}
        />
        {published && (
          <Link to={`/performances/${performanceId}`} className="btn btn-ghost">
            공개 화면 보기
          </Link>
        )}
      </Panel>

      <Panel title="1. 등급별 가격" badge={<StepBadge done={record.gradePrices.length > 0} />} note="관리자가 보내 준 공연장 정보의 등급 ID를 옮겨요. 판매할 좌석이 쓰는 등급을 하나라도 빼면 저장이 거절돼요. 공개 뒤에는 바꿀 수 없어요.">
        <form
          className="stack"
          onSubmit={(event) => {
            event.preventDefault();
            savePrices.mutate();
          }}
        >
          {savePrices.isError && <ErrorNotice error={savePrices.error} />}
          {savePrices.isSuccess && <Done>가격을 저장했어요. {savePrices.data.gradePrices.map((p) => `${p.gradeName} ${won(p.price)}`).join(', ')}</Done>}
          {prices.map((row, index) => (
            <div key={index} className="form-grid">
              <Field id={`price-grade-${index}`} label="등급 ID">
                <input
                  id={`price-grade-${index}`}
                  className="input"
                  type="number"
                  min={1}
                  required
                  value={row.venueSeatGradeId}
                  onChange={(e) => setPrices(prices.map((p, i) => (i === index ? { ...p, venueSeatGradeId: e.target.value } : p)))}
                />
              </Field>
              <Field id={`price-value-${index}`} label="가격(원)">
                <input
                  id={`price-value-${index}`}
                  className="input"
                  type="number"
                  min={1}
                  required
                  value={row.price}
                  onChange={(e) => setPrices(prices.map((p, i) => (i === index ? { ...p, price: e.target.value } : p)))}
                />
              </Field>
            </div>
          ))}
          <div className="actions">
            <button type="button" className="btn btn-ghost" onClick={() => setPrices([...prices, { venueSeatGradeId: '', price: '' }])}>
              등급 추가
            </button>
            {prices.length > 1 && (
              <button type="button" className="btn btn-ghost" onClick={() => setPrices(prices.slice(0, -1))}>
                마지막 등급 빼기
              </button>
            )}
            <button className="btn btn-primary" disabled={savePrices.isPending || published}>
              가격 저장
            </button>
          </div>
        </form>
      </Panel>

      <Panel
        title="2. 회차"
        badge={<StepBadge done={record.scheduleIds.length > 0} label={record.scheduleIds.length > 0 ? `${record.scheduleIds.length}개` : '필요'} />}
        note="시각은 서울 기준이에요. 등록하면 좌석 준비를 시작하고, 준비가 끝나면 예매 오픈 시각부터 판매돼요."
      >
        <form
          className="stack"
          onSubmit={(event) => {
            event.preventDefault();
            addSchedule.mutate();
          }}
        >
          {addSchedule.isError && <ErrorNotice error={addSchedule.error} />}
          {addSchedule.isSuccess && <Done>회차 #{addSchedule.data.id}을 등록했어요.</Done>}
          <div className="form-grid">
            <Field id="schedule-startAt" label="공연 시작">
              <input id="schedule-startAt" className="input" type="datetime-local" required value={schedule.startAt} onChange={(e) => setSchedule({ ...schedule, startAt: e.target.value })} />
            </Field>
            <Field id="schedule-bookingOpenAt" label="예매 오픈">
              <input id="schedule-bookingOpenAt" className="input" type="datetime-local" required value={schedule.bookingOpenAt} onChange={(e) => setSchedule({ ...schedule, bookingOpenAt: e.target.value })} />
            </Field>
            <Field id="schedule-bookingCloseAt" label="예매 마감">
              <input id="schedule-bookingCloseAt" className="input" type="datetime-local" required value={schedule.bookingCloseAt} onChange={(e) => setSchedule({ ...schedule, bookingCloseAt: e.target.value })} />
            </Field>
          </div>
          <button className="btn btn-primary" disabled={addSchedule.isPending}>
            회차 등록
          </button>
        </form>
        <div className="chips">
          {record.scheduleIds.map((id) => (
            <Link key={id} to={`/seller/schedules/${id}`} className="chip" style={{ display: 'inline-flex', alignItems: 'center', textDecoration: 'none' }}>
              회차 #{id}
            </Link>
          ))}
        </div>
      </Panel>

      <Panel
        title="3. 공개"
        badge={<StepBadge done={published} label={published ? '공개됨' : '초안'} />}
        note="소개·포스터, 취소되지 않은 회차, 판매 좌석, 등급별 가격이 모두 있어야 공개돼요. 공개를 되돌리는 기능은 없어요."
      >
        {publish.isError && <ErrorNotice error={publish.error} />}
        {publish.isSuccess && <Done>공개했어요.</Done>}
        {!published && missing.length > 0 && <p className="hint">먼저 채워야 해요: {missing.join(', ')}</p>}
        <button type="button" className="btn btn-primary" disabled={publish.isPending || published || missing.length > 0} onClick={() => publish.mutate()}>
          {published ? '공개됨' : '공개하기'}
        </button>
      </Panel>

      {published && (
        <Panel title="좌석 배치" note="공개 좌석 배치 조회로 등급별 좌석 수를 확인해요.">
          {layout.isPending && <Loading />}
          {layout.isError && <ErrorNotice error={layout.error} />}
          <div className="grades">
            {[...byGrade.entries()].map(([name, entry]) => (
              <div key={name} className="grade">
                <div className="name">{name}</div>
                <div className="value num">
                  {entry.sellable}/{entry.total}석
                </div>
                <div className="name num">{entry.price === null ? '가격 없음' : won(entry.price)}</div>
              </div>
            ))}
          </div>
        </Panel>
      )}
    </main>
  );
}
