import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { VenueRecord } from '../lib/adminManagement';
import { GlassVenueIcon } from './GlassVenueIcon';
export function AdminVenueList({ records, selected }: { records: VenueRecord[]; selected?: number }) {
  const [keyword, setKeyword] = useState(''),
    [limit, setLimit] = useState(10);
  const list = records.filter(
    (item) =>
      item.name.toLocaleLowerCase().includes(keyword.trim().toLocaleLowerCase()) ||
      String(item.id).includes(keyword.trim()),
  );
  return (
    <aside className="admin-venue-index">
      <label className="sr-only" htmlFor="admin-venue-search">
        저장된 공연장 검색
      </label>
      <input
        id="admin-venue-search"
        className="input"
        type="search"
        placeholder="공연장명 또는 ID 검색"
        value={keyword}
        onChange={(e) => {
          setKeyword(e.target.value);
          setLimit(10);
        }}
      />
      <div className="admin-venue-list">
        {list.slice(0, limit).map((item) => (
          <Link
            key={item.id}
            className={selected === item.id ? 'active' : ''}
            aria-current={selected === item.id ? 'page' : undefined}
            to={`/admin/venues/${item.id}`}
          >
            <GlassVenueIcon />
            <div className="admin-venue-copy">
              <strong>{item.name}</strong>
              <span>
                {item.region || '지역 미확인'} · ID {item.id}
              </span>
            </div>
          </Link>
        ))}
        {!list.length && <p className="seller-empty">저장된 공연장이 없어요.</p>}
      </div>
      {list.length > limit && (
        <button className="chip" type="button" onClick={() => setLimit(limit + 10)}>
          공연장 더 보기
        </button>
      )}
      <p className="account-hint">이 브라우저의 기록입니다. 서버 전체 목록과 다를 수 있어요.</p>
      <Link className="admin-create-link" to="/admin/venues">
        ＋ 공연장 등록
      </Link>
    </aside>
  );
}
