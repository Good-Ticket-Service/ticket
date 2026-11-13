import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { SellerScheduleList } from '../../components/SellerScheduleList';
import { useLocalRecords } from '../../lib/localRecords';
import { PERFORMANCE_RECORDS, type PerformanceRecord } from '../../lib/sellerPerformances';

export function SellerSchedulesPage() {
  const navigate = useNavigate();
  const { records } = useLocalRecords<PerformanceRecord>(PERFORMANCE_RECORDS);
  const [performanceId, setPerformanceId] = useState('');
  const [scheduleId, setScheduleId] = useState('');
  const performances = performanceId ? records.filter((item) => String(item.id) === performanceId) : records;
  const ids = [...new Set(performances.flatMap((item) => item.scheduleIds))];
  return (
    <main className="content seller-page stack">
      <header className="seller-page-heading">
        <div>
          <h1 className="section-marker">회차 관리</h1>
          <p>공연별 회차와 좌석 준비 상태를 확인하세요.</p>
        </div>
      </header>
      <section className="panel stack">
        <h2 className="panel-title">회차 ID로 조회</h2>
        <form
          className="actions"
          onSubmit={(event) => {
            event.preventDefault();
            if (/^[1-9]\d*$/.test(scheduleId) && Number.isSafeInteger(Number(scheduleId)))
              navigate(`/seller/schedules/${scheduleId}`);
          }}
        >
          <div className="field" style={{ flex: 1, minWidth: 0 }}>
            <label htmlFor="seller-schedule-id">회차 ID</label>
            <input
              id="seller-schedule-id"
              className="input"
              type="number"
              min={1}
              max={Number.MAX_SAFE_INTEGER}
              step={1}
              required
              value={scheduleId}
              onChange={(event) => setScheduleId(event.target.value)}
              placeholder="회차 ID를 입력하세요"
            />
          </div>
          <button className="btn btn-ghost" style={{ alignSelf: 'flex-end' }}>
            회차 열기
          </button>
        </form>
      </section>
      <section className="panel stack">
        <div className="field">
          <label htmlFor="schedule-performance">공연 선택</label>
          <select
            id="schedule-performance"
            className="input"
            value={performanceId}
            onChange={(event) => setPerformanceId(event.target.value)}
          >
            <option value="">저장된 전체 공연</option>
            {records.map((item) => (
              <option key={item.id} value={item.id}>
                {item.title} · ID {item.id}
              </option>
            ))}
          </select>
        </div>
        <p className="account-hint">
          이 브라우저에 저장된 회차 {ids.length}개입니다. 준비·판매 상태는 서버에서 조회합니다. 다른 곳에서
          등록한 회차는 위에서 ID로 열어 주세요.
        </p>
        <SellerScheduleList key={performanceId} ids={ids} />
      </section>
    </main>
  );
}
