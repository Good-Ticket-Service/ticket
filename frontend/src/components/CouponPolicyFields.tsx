import type { Coupon, CouponIssueStatus } from '../api/manageTypes';
import { GENRE_OPTIONS } from '../lib/genres';
import type { CouponForm } from '../lib/adminCoupons';
export function CouponPolicyFields({
  value,
  onChange,
  status = 'UPCOMING',
  original,
  disabled = false,
}: {
  value: CouponForm;
  onChange: (v: CouponForm) => void;
  status?: CouponIssueStatus;
  original?: Coupon;
  disabled?: boolean;
}) {
  const locked = status !== 'UPCOMING',
    ended = status === 'ENDED';
  const set = (key: keyof CouponForm, text: string) =>
    onChange({
      ...value,
      [key]: text,
      ...(key === 'category' && text === 'WELCOME' ? { validDays: '7' } : {}),
    });
  const input = (
    key: keyof CouponForm,
    label: string,
    options: { min?: number; max?: number; optional?: boolean; disabled?: boolean; type?: string } = {},
  ) => (
    <div className="field" key={key}>
      <label htmlFor={`policy-${key}`}>{label}</label>
      <input
        id={`policy-${key}`}
        className="input"
        required={!options.optional}
        type={options.type ?? 'number'}
        min={options.type === 'datetime-local' ? undefined : (options.min ?? 1)}
        max={options.type === 'datetime-local' ? undefined : (options.max ?? 2147483647)}
        value={value[key]}
        disabled={disabled || options.disabled}
        onChange={(e) => set(key, e.target.value)}
        onInput={options.type === 'datetime-local' ? (e) => set(key, e.currentTarget.value) : undefined}
      />
    </div>
  );
  return (
    <fieldset className="seller-fieldset stack" disabled={disabled}>
      <section className="stack">
        <h3 className="panel-title">기본 정보</h3>
        <div className="field">
          <label htmlFor="policy-name">쿠폰 이름</label>
          <input
            id="policy-name"
            className="input"
            required
            maxLength={100}
            pattern=".*\S.*"
            value={value.name}
            onChange={(e) => set('name', e.target.value)}
          />
        </div>
        <div className="form-grid">
          <div className="field">
            <label htmlFor="policy-category">쿠폰 구분</label>
            <select
              id="policy-category"
              className="input"
              disabled={locked}
              value={value.category}
              onChange={(e) => set('category', e.target.value)}
            >
              <option value="GENERAL">일반 쿠폰</option>
              <option value="WELCOME">웰컴 쿠폰</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="policy-genre">적용 장르</label>
            <select
              id="policy-genre"
              className="input"
              disabled={locked}
              value={value.genre}
              onChange={(e) => set('genre', e.target.value)}
            >
              <option value="" disabled={!!original?.genre}>
                전체 장르
              </option>
              {GENRE_OPTIONS.map((genre) => (
                <option key={genre.value} value={genre.value}>
                  {genre.label}
                </option>
              ))}
            </select>
          </div>
        </div>
        {original?.genre && (
          <p className="account-hint">
            장르 쿠폰은 수정으로 전체 장르로 전환할 수 없어요. 발급 이력이 없으면 삭제 후 새로 만들어 주세요.
          </p>
        )}
      </section>
      <section className="stack admin-seat-section">
        <h3 className="panel-title">할인 정책</h3>
        <div className="field">
          <label htmlFor="policy-discountType">할인 종류</label>
          <select
            id="policy-discountType"
            className="input"
            disabled={locked}
            value={value.discountType}
            onChange={(e) => set('discountType', e.target.value)}
          >
            <option value="FIXED">정액 할인</option>
            <option value="RATE">정률 할인</option>
          </select>
        </div>
        <div className="form-grid">
          {value.discountType === 'FIXED' ? (
            input('discountAmount', '할인 금액 (원)', { disabled: locked })
          ) : (
            <>
              {input('discountRate', '할인율 (%)', { max: 99, disabled: locked })}
              {input('maxDiscountAmount', '최대 할인액 (원, 선택)', { optional: true, disabled: locked })}
            </>
          )}
          {input('minBaseAmount', '최소 티켓 금액 (원, 선택)', { optional: true, disabled: locked })}
        </div>
        <p className="account-hint">
          최소 금액은 할인 전 티켓 정가 기준입니다. 정률은 원 미만을 버리고 결제 시 최소 100원을 남깁니다.
        </p>
      </section>
      <section className="stack admin-seat-section">
        <h3 className="panel-title">발급 설정</h3>
        <div className="form-grid">
          {input('totalQuantity', '총 발급 수량', { disabled: ended })}
          {input('validDays', '발급 후 유효 기간 (일)', { disabled: locked || value.category === 'WELCOME' })}
          {input('issueStartAt', '발급 시작', { type: 'datetime-local', disabled: locked })}
          {input('issueEndAt', '발급 종료', { type: 'datetime-local', disabled: ended })}
        </div>
        <p className="account-hint">
          서울 시각 기준입니다. 과거 시작 시각은 서버의 현재 시각으로 저장됩니다.
          {value.category === 'WELCOME' ? ' 웰컴 쿠폰은 발급 후 7일간 유효합니다.' : ''}
        </p>
      </section>
    </fieldset>
  );
}
