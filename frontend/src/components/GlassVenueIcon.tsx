import { useId } from 'react';

/** 기존 공연장 실루엣에 반투명 재질과 가장자리 반사를 적용한다. */
export function GlassVenueIcon() {
  const id = useId().replace(/:/g, '');
  return (
    <span className="glass-venue-icon" aria-hidden="true">
      <svg viewBox="0 0 72 72" fill="none">
        <defs>
          <linearGradient id={`${id}-stone`} x1="14" y1="12" x2="61" y2="64" gradientUnits="userSpaceOnUse">
            <stop stopColor="var(--venue-highlight, #fff)" stopOpacity=".8" />
            <stop offset=".28" stopColor="var(--venue-body, #cbb3e8)" stopOpacity=".68" />
            <stop offset=".67" stopColor="var(--venue-body, #cbb3e8)" stopOpacity=".18" />
            <stop offset="1" stopColor="#e34234" stopOpacity=".42" />
          </linearGradient>
          <linearGradient id={`${id}-edge`} x1="16" y1="10" x2="58" y2="65" gradientUnits="userSpaceOnUse">
            <stop stopColor="var(--venue-highlight, #fff)" stopOpacity=".95" />
            <stop offset=".5" stopColor="#d6bfee" stopOpacity=".65" />
            <stop offset="1" stopColor="#f08b80" stopOpacity=".8" />
          </linearGradient>
          <linearGradient id={`${id}-column`} x1="24" y1="30" x2="29" y2="54" gradientUnits="userSpaceOnUse">
            <stop stopColor="var(--venue-highlight, #fff)" stopOpacity=".72" />
            <stop offset=".3" stopColor="#d9c2f0" stopOpacity=".28" />
            <stop offset="1" stopColor="var(--venue-body, #cbb3e8)" stopOpacity=".62" />
          </linearGradient>
        </defs>
        <path d="M15 32h46v28H15Z" fill="#b79bcf" fillOpacity=".09" />
        <g fill={`url(#${id}-stone)`} stroke={`url(#${id}-edge)`} strokeWidth=".9" strokeLinejoin="round">
          <path d="m11 27 25-16 25 16-3 5H14Z" />
          <path d="m16 27 20-12 20 12H16Z" fillOpacity=".4" />
          <path d="M15 55h43l4 5H11l4-5Z" />
          <path d="M11 61h51l3 5H8l3-5Z" />
          {[17, 28, 39, 50].map((x) => (
            <g key={x}>
              <rect x={x} y="32" width="6" height="23" rx="1.7" fill={`url(#${id}-column)`} />
              <path d={`M${x + 1.5} 34v17`} stroke="#fff" strokeOpacity=".55" />
              <path d={`M${x - 1} 32h8M${x - 1} 55h8`} strokeOpacity=".7" />
            </g>
          ))}
        </g>
        <path d="m13 26 23-14 14 8M10 63h31" stroke="#fff" strokeOpacity=".7" strokeWidth=".8" />
        <path d="M44 65h19" stroke="#f0776b" strokeWidth="1.6" strokeLinecap="round" />
      </svg>
    </span>
  );
}
