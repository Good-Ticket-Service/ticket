import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Title } from '../../components/Common';
import { Panel } from '../../components/Form';
import { useLocalRecords } from '../../lib/localRecords';

/** 판매자 공연 조회 API가 없어, 만든 공연의 version과 회차를 이 브라우저에 기억한다. */
export interface PerformanceRecord {
  id: number;
  title: string;
  venueId: number;
  version: number;
  publicationStatus: string;
  gradePrices: { venueSeatGradeId: number; gradeName: string; price: number }[];
  scheduleIds: number[];
  /** 초안 등록 때 소개·포스터를 넣었는지. 이 값이 생기기 전에 만든 기록은 모른다. */
  hasIntro?: boolean;
}

export const PERFORMANCE_RECORDS = 'ticket.seller.performances';

export function SellerHomePage() {
  const navigate = useNavigate();
  const performances = useLocalRecords<PerformanceRecord>(PERFORMANCE_RECORDS);
  const [scheduleId, setScheduleId] = useState('');
  return (
    <main className="content medium stack" style={{ gap: 24 }}>
      <Title en="Seller" ko="판매 관리" />
      <div className="actions">
        <Link to="/seller/performances/new" className="btn btn-primary">
          새 공연 등록
        </Link>
        <Link to="/seller/settlements" className="btn btn-ghost">
          정산
        </Link>
      </div>
      <Panel title="이 브라우저에서 만든 공연" note="판매자 공연 목록 API가 없어 여기서 만든 것만 기억해요. 지우기는 이 기억만 지우고 서버의 공연은 그대로예요.">
        {performances.records.length === 0 && <p className="muted">아직 없어요.</p>}
        {performances.records.map((item) => (
          <div key={item.id} className="item-row">
            <Link to={`/seller/performances/${item.id}`} className="item">
              <span>
                <span className="name">{item.title}</span>
                <br />
                <span className="sub num">
                  공연 ID {item.id} · 공연장 {item.venueId} · 회차 {item.scheduleIds.length}개
                </span>
              </span>
              <span className={item.publicationStatus === 'PUBLISHED' ? 'pill on' : 'pill'}>
                {item.publicationStatus === 'PUBLISHED' ? '공개' : '초안'}
              </span>
            </Link>
            <button type="button" className="chip" aria-label={`${item.title} 기록 지우기`} onClick={() => performances.remove(item.id)}>
              지우기
            </button>
          </div>
        ))}
      </Panel>
      <Panel title="회차 조회" note="좌석 준비 상태와 회차 version을 확인해요.">
        <form
          className="actions"
          onSubmit={(event) => {
            event.preventDefault();
            if (scheduleId) navigate(`/seller/schedules/${scheduleId}`);
          }}
        >
          <input
            className="input"
            style={{ flex: '1 1 160px', width: 'auto' }}
            type="number"
            min={1}
            placeholder="회차 ID"
            aria-label="회차 ID"
            value={scheduleId}
            onChange={(e) => setScheduleId(e.target.value)}
          />
          <button className="btn btn-ghost">열기</button>
        </form>
      </Panel>
    </main>
  );
}
