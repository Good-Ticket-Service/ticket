import { useId } from 'react';

/** 장르별 형태에 반투명 채움·가장자리 반사를 더한 작은 유리 아이콘이다. */
export function GenreIcon({ genre }: { genre: string }) {
  const id = useId().replace(/:/g, '');
  const warm = genre === 'CONCERT' || genre === 'PLAY';
  const lime = genre === 'CLASSICAL' || genre === 'SPORTS' || genre === 'KOREAN_MUSIC';
  const color = warm ? '#ec8c83' : lime ? '#d4e996' : '#cbb3e8';
  return (
    <span className="genre-glass" aria-hidden="true">
      <svg viewBox="0 0 64 64" fill="none">
        <defs>
          <linearGradient id={`${id}-glass`} x1="12" y1="8" x2="51" y2="58" gradientUnits="userSpaceOnUse">
            <stop stopColor="#fff" stopOpacity=".85" />
            <stop offset=".27" stopColor={color} stopOpacity=".7" />
            <stop offset=".65" stopColor={color} stopOpacity=".2" />
            <stop offset="1" stopColor={color} stopOpacity=".65" />
          </linearGradient>
          <linearGradient id={`${id}-edge`} x2="1" y2="1">
            <stop stopColor="#fff" stopOpacity=".85" />
            <stop offset="1" stopColor={color} stopOpacity=".3" />
          </linearGradient>
        </defs>
        <g fill={`url(#${id}-glass)`} stroke={`url(#${id}-edge)`} strokeWidth="1.25" strokeLinejoin="round">
          {genre === '' && (
            <>
              {[
                [12, 12],
                [35, 12],
                [12, 35],
                [35, 35],
              ].map(([x, y]) => (
                <rect key={`${x}-${y}`} x={x} y={y} width="17" height="17" rx="5" />
              ))}
            </>
          )}
          {genre === 'MUSICAL' && (
            <>
              <path d="m9 16 25 3-3 17c-1 10-17 15-21 1Z" />
              <path d="m29 22 25-5-1 21c0 12-15 16-20 6Z" />
              <path
                d="m14 27 5 2m6-1 4-1m-13 9q6 5 12-1m10-5 4 1m5-2 4-1m-13 12q6-4 11-1"
                fill="none"
                stroke="#352b45"
                strokeWidth="2"
              />
            </>
          )}
          {genre === 'CONCERT' && (
            <>
              <path d="m28 34 7 6-19 16-5-5Z" />
              <rect x="26" y="9" width="23" height="31" rx="11.5" transform="rotate(38 37 24)" />
              <path d="m30 15 15 12m-18-7 15 12" fill="none" />
            </>
          )}
          {genre === 'PLAY' && (
            <>
              <path d="M10 15h44v36H10Z" fillOpacity=".25" />
              <path d="M11 16h18c-2 14-7 20-17 23Zm42 0H35c2 14 7 20 17 23Z" />
              <path d="m12 39 9-4-2 16h-8Zm40 0-9-4 2 16h8Z" />
              <path d="M9 12h46v6H9Z" />
            </>
          )}
          {(genre === 'CLASSICAL' || genre === 'KOREAN_MUSIC') && (
            <>
              <path d="M19 13c-12 9-8 34 13 39 21-5 25-30 13-39l-5 4c9 14 5 23-8 28-13-5-17-14-8-28Z" />
              <path d="M19 13h26M24 15v26m6-26v30m6-30v30m6-30v26" fill="none" strokeWidth="2" />
              <circle cx="19" cy="12" r="4" />
              <circle cx="45" cy="12" r="4" />
            </>
          )}
          {genre === 'DANCE' && (
            <>
              <path d="M12 44C20 6 56 8 51 23c-4 13-29 0-34 18 16-14 37-2 30 11-5-15-20-16-35-8Z" />
              <path d="M18 39c13-18 28-11 33-20" fill="none" />
            </>
          )}
          {genre === 'SPORTS' && (
            <>
              <circle cx="32" cy="32" r="21" />
              <path
                d="m32 22 10 7-4 12H26l-4-12ZM11 31l11-2m20 0 11 2M32 11v11M18 47l8-6m12 0 8 6"
                fill="none"
              />
            </>
          )}
          {genre === 'CIRCUS_MAGIC' && (
            <>
              <path d="m14 49 30-31 6 6-30 31Z" />
              <path d="m19 10 2 7 7 2-7 2-2 7-2-7-7-2 7-2Zm29 28 2 6 6 2-6 2-2 6-2-6-6-2 6-2Z" />
            </>
          )}
          {genre === 'MULTIDISCIPLINARY' && (
            <>
              <path d="m32 10 23 14-23 14L9 24Z" />
              <path d="m9 33 23 14 23-14M9 42l23 14 23-14" fill="none" strokeWidth="3" />
            </>
          )}
        </g>
      </svg>
    </span>
  );
}
