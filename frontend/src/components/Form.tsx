import type { ReactNode } from 'react';

/** 라벨·입력·도움말 한 칸. id는 라벨과 입력을 잇는 데 쓴다. */
export function Field({ id, label, hint, children }: { id: string; label: string; hint?: string; children: ReactNode }) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children}
      {hint && <span className="hint">{hint}</span>}
    </div>
  );
}

/** 관리 화면의 한 덩어리. 제목과 설명을 위에 두고 내용을 아래에 둔다. */
export function Panel({ title, note, children }: { title: string; note?: ReactNode; children: ReactNode }) {
  return (
    <section className="panel stack">
      <div>
        <h2 className="panel-title">{title}</h2>
        {note && <p className="muted panel-note">{note}</p>}
      </div>
      {children}
    </section>
  );
}

/** 이름-값 목록. 값이 null이면 '-'로 보인다. */
export function Facts({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="rows">
      {items.map(([label, value]) => (
        <div key={label} className="row">
          <dt>{label}</dt>
          <dd className="num">{value ?? '-'}</dd>
        </div>
      ))}
    </dl>
  );
}

export function Done({ children }: { children: ReactNode }) {
  return (
    <p className="done" role="status">
      {children}
    </p>
  );
}
