# {도메인} API 명세

<!-- docs/{도메인}/api.md 양식. 예시: payment/api.md -->

- 상태: 작성 전 | 작성 중 | 작성 완료
- 담당:

외부 API(`/api/**`)만 둔다. 다른 서비스가 부르는 내부 API는 [shared/internal-api/](../shared/README.md)에 두고 링크한다. 공통 헤더·응답 형식은 [shared/conventions.md](../shared/conventions.md)를 따른다.

## 1. 외부 API

| Method | URI | 권한·허용 호출자 | 기능 |
|---|---|---|---|
| | | | |

### {METHOD} /api/{경로} — {기능}

- 권한·허용 호출자:
- 필수 헤더:
- 성공 HTTP:

요청

```json
{ }
```

응답 (`ApiResponse.data`)

```json
{ }
```

오류

| 상태 | 코드 | 때 |
|---|---|---|
| | | |

## 2. 내부 API

- 제공: [shared/internal-api/{도메인}.md](../shared/README.md)
- 사용: {부르는 다른 도메인의 계약 링크}
