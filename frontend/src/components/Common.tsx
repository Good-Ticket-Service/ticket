import { errorMessage } from '../api/client';
import type { ReservationStatus } from '../api/types';

export function Title({ en, ko }: { en: string; ko: string }) {
  return (
    <h1 className="title">
      <span className="en">{en}</span>
      <span className="ko">{ko}</span>
    </h1>
  );
}

export function SectionTitle({ en, ko }: { en: string; ko: string }) {
  return (
    <h2 className="section-title">
      <span className="en">{en}</span>
      <span className="ko">{ko}</span>
    </h2>
  );
}

export function Loading() {
  return (
    <p className="loading" role="status">
      불러오는 중이에요
    </p>
  );
}

export function ErrorNotice({ error }: { error: unknown }) {
  return (
    <p className="notice" role="alert">
      {errorMessage(error)}
    </p>
  );
}

export const GENRE_LABEL: Record<string, string> = {
  MUSICAL: '뮤지컬',
  CONCERT: '콘서트',
  PLAY: '연극',
  SPORTS: '스포츠',
};

/** 주소가 깨진 포스터는 지워서 아래의 그래픽이 보이게 한다. */
export function Poster({ src }: { src: string | null }) {
  if (!src) return null;
  return <img src={src} alt="" onError={(event) => event.currentTarget.remove()} />;
}

const ARTS = ['art-warm', 'art-truss', 'art-wood', 'art-steel'];

/** 포스터가 없는 공연에 ID로 고정된 그래픽을 준다. 같은 공연은 항상 같은 그림이다. */
export function artClass(id: number): string {
  return ARTS[id % ARTS.length];
}

export const STATUS_LABEL: Record<ReservationStatus, string> = {
  HOLD: '결제 대기',
  PAYMENT_IN_PROGRESS: '결제 확인 중',
  CONFIRMED: '예매 완료',
  CANCELING: '취소 처리 중',
  EXPIRED: '시간 만료',
  RELEASED: '선점 해제',
  CANCELED: '취소 완료',
};
