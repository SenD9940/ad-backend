# 고객 신청 · 9,900원 토스페이먼츠 결제

고객이 워크스페이스의 **기술 지원**에서 작업 내용과 접근 범위를 선택하고 약관에 동의한 뒤 **신청하고 9,900원 결제**를 누릅니다. 토스페이먼츠 결제가 서버에서 확인되면 관리자는 요청을 열어 **지원 시작**으로 고객 화면에 접속합니다. 고객에게 다시 승인을 요청하거나 관리자가 입금을 수동 확인할 필요가 없습니다.

## 설정과 실행

기존 운영/지원 마이그레이션을 적용한 공유 DB에 아래 SQL을 순서대로 한 번 적용합니다. 애플리케이션이 자동 실행하지 않습니다.

1. `db/migrations/20260928_customer_support_requests.sql`
2. `db/migrations/20260928_support_payments.sql`

첫 SQL은 기존 관리자 등록 건을 `ADMIN`으로 유지하고 고객 신청의 담당자를 미배정으로 저장할 수 있게 합니다. 두 번째 SQL은 결제 주문, 암호화된 결제키, 승인·조회 상태를 저장합니다. 기존 DB 암호화 설정을 사용합니다.

일반 API 서버의 `application-local.yml`에 아래 설정을 추가하거나, `TOSS_PAYMENTS_CLIENT_KEY` / `TOSS_PAYMENTS_SECRET_KEY` 환경변수를 설정합니다. **동일한 상점·환경의 주문서형/결제창형 연동 키**를 사용합니다. 시크릿 키는 프론트엔드 환경변수에 넣지 않습니다.

```yaml
app:
  support:
    toss:
      client-key: "<토스 클라이언트 키>"
      secret-key: "<토스 시크릿 키>"
```

현재 개발 PC의 Git 제외 파일 `api/src/main/resources/application-local.yml`에는 토스 공식 문서의 공개 테스트 키 한 쌍을 설정했습니다. `local` 프로필에서는 개인 키 없이 9,900원 테스트 결제를 진행할 수 있으며 실제 금액은 출금되지 않습니다. `TOSS_PAYMENTS_CLIENT_KEY` / `TOSS_PAYMENTS_SECRET_KEY`를 지정하면 해당 값이 우선합니다. 공통·운영 설정에는 공개 키 기본값을 넣지 않았습니다.

공개 문서 키로 만든 결제는 본인 개발자센터의 결제내역에서 확인할 수 없습니다. 개인 상점의 내역·웹훅 설정까지 검증하려면 본인 상점의 호환되는 테스트 키 한 쌍으로 교체합니다. [토스 API 키 안내](https://docs.tosspayments.com/reference/using-api/api-keys)와 [공개 테스트 키 FAQ](https://docs.tosspayments.com/resources/faq)를 참고하세요.

키가 없는 환경에서는 고객 화면에 결제 준비 중으로 표시하고 신청·결제를 차단합니다. 가짜 결제 완료 처리는 하지 않습니다. 설정 후 `api`(8480)와 `admin-api`(8481)를 재시작합니다. 프론트엔드는 `http://localhost:3400`을 사용합니다.

Toss 개발자센터에서 `PAYMENT_STATUS_CHANGED` 웹훅을 다음 공개 HTTPS 주소로 등록합니다.

```text
https://<API 도메인>/open-api/support/payments/toss/webhook
```

서버는 웹훅 본문의 결제 완료 주장이나 금액을 믿지 않고 저장된 결제키로 토스 API를 다시 조회합니다. 아직 승인 콜백으로 결제키가 저장되지 않은 주문의 조기 웹훅은 무시합니다. 개발 PC의 localhost는 외부 웹훅을 직접 받을 수 없으므로 웹훅 검증에는 공개 테스트 서버 또는 HTTPS 터널이 필요합니다. 동기 결제 결과와 고객의 **결제 상태 확인**도 서버 조회를 사용합니다.

브라우저 결제 결과 주소는 현재 프론트엔드 출처를 기준으로 만들어집니다.

```text
/workspaces/{workspaceId}/support/{ticketId}/payment/success
/workspaces/{workspaceId}/support/{ticketId}/payment/fail
```

토스 연동 키와 결제 UI 설정은 [공식 결제창형 연동 가이드](https://docs.tosspayments.com/guides/v2/payment-widget/integration-window)를 참고합니다. 테스트 키로 먼저 결제·실패·취소·조회·웹훅 흐름을 확인한 후 운영 키를 적용합니다.

## 신청·결제 API

일반 서비스 Access Token과 현재 워크스페이스 소유자 권한이 필요합니다. 지원 접속 토큰으로 대신 신청하거나 결제할 수 없습니다. JSON 필드는 snake_case입니다.

| 메서드·경로 (`/api/workspaces/{workspaceId}/support` 기준) | 내용 |
| --- | --- |
| `GET /offer` | 서버 고정 금액 9,900원, 신청 가능 여부, 약관 버전·원문 |
| `POST /tickets` | 고객 신청과 해당 작업의 화면 접근 동의를 함께 기록 |
| `POST /tickets/{ticketId}/payment-order` | 서버가 주문번호·금액·공개 클라이언트 키·무작위 고객키 발급 |
| `POST /tickets/{ticketId}/payment-confirm` | `payment_key`, `order_id`, `amount` 검증 후 토스 승인 |
| `GET /tickets/{ticketId}/payment?orderId=...` | 조회를 통한 결제 결과 확인. 주문번호 생략 시 이 요청의 최근 주문 조회 |
| `POST /tickets/{ticketId}/revoke` | 신청 취소·접근 철회. 메모 없는 요청은 `{}` |

신청 예시:

```json
{
  "title": "Meta 광고 설정 지원",
  "description": "선택한 광고의 예산과 게재 상태를 점검해 주세요.",
  "access_mode": "OPERATE",
  "terms_version": "support-2026-09-28-v1",
  "accepted_terms": true
}
```

고객·워크스페이스·금액·결제 상태·담당자는 서버가 결정합니다. `READ_ONLY`는 조회만, `OPERATE`는 신청한 작업의 조회·조작을 허용합니다. 금액이나 고객 ID 등을 신청 JSON에 추가하면 거절합니다. 약관 원문은 서버 `SupportTerms`에 두고 신청 시 버전·선택 범위·금액과 함께 스냅샷을 저장합니다. 약관 변경 후에는 버전을 함께 변경합니다.

고객 신청은 `CUSTOMER`, `APPROVED`, `UNPAID`, 담당자 미배정으로 시작합니다. 신청 시 이미 동의했으므로 `APPROVED`는 추가 승인이 필요하다는 뜻이 아닙니다. 토스의 검증된 `DONE` 결과가 확인되어야 `PAID`로 바뀝니다. 관리자 수동 수납 API로 고객 신청을 결제 완료로 바꿀 수 없습니다.

## 중복 승인과 결과 불확실성

승인 전에 주문 상태와 결제키를 별도 트랜잭션으로 저장합니다. 원격 승인에는 주문별 멱등키를 사용하고, 같은 콜백이 반복되면 승인 POST를 다시 보내지 않고 조회합니다. 타임아웃·서버 오류·응답 불일치는 결제 실패로 단정하지 않습니다. 결과를 확인할 수 없는 주문은 새 주문 발급을 막아 중복 결제를 방지합니다.

주문·고객·워크스페이스·KRW·9,900원·잔액을 함께 확인합니다. URL의 성공 표시만으로 결제 완료 처리하지 않습니다. 결제 도중 고객이 요청을 철회하거나 소유자가 바뀌어도 실제 결제 내역은 기록하지만, 취소된 요청의 지원 접근을 다시 허용하지 않습니다. [토스 승인·조회 API](https://docs.tosspayments.com/reference)와 [웹훅 규격](https://docs.tosspayments.com/reference/using-api/webhook-events)을 사용합니다.

## 운영과 약관

신청 동의는 7일, 지원 접속은 회당 최대 15분입니다. 결제가 확인된 미배정 요청은 **지원 시작**을 실행한 운영자에게 원자적으로 배정됩니다. 고객 철회·만료·소유권 변경·계정 정지는 기존 접근 검증에 반영됩니다.

앱의 **지원 요청 취소**는 지원 접근을 중단합니다. 결제 취소·환불은 토스 상점관리자에서 처리하며, 결제 완료 후 취소된 요청은 어드민에서 **환불 확인 필요**로 표시합니다. 토스의 결제 취소 웹훅 또는 결제 재조회가 반영되면 결제 완료 상태와 활성 지원 접속을 해제합니다. 앱에는 자동 환불 API를 추가하지 않았습니다.

화면의 약관은 지원 작업의 범위·요금·접근 기간·철회·기록에 관한 동의문입니다. 전체 서비스 약관을 대체하지 않으며, 서비스 사업자의 실제 정보·고객 연락처·제공 일정·환불 기준과 함께 운영해야 합니다. 접근 동의로 청약철회·환불 권리를 포기하도록 하지 않습니다.

기존 관리자가 대신 등록한 `ADMIN` 요청은 종전의 고객 승인과 수동 수납 흐름을 유지합니다. 자세한 권한·지원 토큰 구조는 [기술 지원 운영 문서](support-operations.md)를 참고합니다.
