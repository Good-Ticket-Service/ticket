import { useId, type ReactNode } from 'react';
import { Brand } from './Brand';

export function AuthShell({ children }: { children: ReactNode }) {
  const id = useId().replace(/:/g, '');
  const shape =
    'M28 14H332Q346 14 346 28V81C319 81 319 139 346 139V192Q346 206 332 206H28Q14 206 14 192V139C41 139 41 81 14 81V28Q14 14 28 14Z';
  return (
    <main className="content auth-content">
      <div className="auth-layout">
        <div className="auth-art" aria-hidden="true">
          <div className="auth-glass-object">
            <svg className="auth-glass-ticket" viewBox="0 0 360 220" fill="none">
              <defs>
                <linearGradient
                  id={`${id}-body`}
                  x1="30"
                  y1="10"
                  x2="330"
                  y2="210"
                  gradientUnits="userSpaceOnUse"
                >
                  <stop stopColor="#fff" stopOpacity=".23" />
                  <stop offset=".22" stopColor="#cbb3e8" stopOpacity=".13" />
                  <stop offset=".6" stopColor="#fff" stopOpacity=".025" />
                  <stop offset="1" stopColor="#e34234" stopOpacity=".21" />
                </linearGradient>
                <linearGradient
                  id={`${id}-edge`}
                  x1="25"
                  y1="15"
                  x2="335"
                  y2="210"
                  gradientUnits="userSpaceOnUse"
                >
                  <stop stopColor="#fff" stopOpacity=".9" />
                  <stop offset=".22" stopColor="#cbb3e8" stopOpacity=".8" />
                  <stop offset=".65" stopColor="#fff" stopOpacity=".16" />
                  <stop offset="1" stopColor="#e34234" stopOpacity=".85" />
                </linearGradient>
                <linearGradient
                  id={`${id}-shine`}
                  x1="42"
                  y1="20"
                  x2="270"
                  y2="180"
                  gradientUnits="userSpaceOnUse"
                >
                  <stop stopColor="#fff" stopOpacity=".18" />
                  <stop offset=".55" stopColor="#fff" stopOpacity="0" />
                </linearGradient>
                <filter id={`${id}-glow`} x="-40%" y="-60%" width="180%" height="220%">
                  <feGaussianBlur stdDeviation="4" />
                </filter>
              </defs>
              <path
                d={shape}
                transform="translate(3 5)"
                stroke="#cbb3e8"
                strokeOpacity=".18"
                strokeWidth="3"
              />
              <path d={shape} fill={`url(#${id}-body)`} stroke={`url(#${id}-edge)`} strokeWidth="1.2" />
              <path d={shape} transform="translate(9 6) scale(.95)" stroke="#fff" strokeOpacity=".09" />
              <path
                d="M30 17H170M330 202H250"
                stroke={`url(#${id}-edge)`}
                strokeWidth="6"
                filter={`url(#${id}-glow)`}
              />
              <path d="M31 18H175" stroke="#eee5fb" strokeOpacity=".7" strokeWidth="1.2" />
              <path d="M334 201H256" stroke="#f2887e" strokeOpacity=".8" strokeWidth="1.3" />
              <path
                d="M45 25H263L178 194H45Q29 194 29 180V144Q53 110 29 76V41Q29 25 45 25Z"
                fill={`url(#${id}-shine)`}
              />
              <path d="M280 30V190" stroke="#eee5fb" strokeOpacity=".3" strokeDasharray="5 6" />
            </svg>
            <div className="auth-glass-brand">
              <Brand />
              <span>YOUR NEXT LIVE</span>
              <i />
            </div>
          </div>
          <div className="auth-art-caption">
            <span>safe ticket</span>
            <p>다음 공연을 만나는 곳</p>
          </div>
        </div>
        <section className="auth-form-panel">{children}</section>
      </div>
    </main>
  );
}
