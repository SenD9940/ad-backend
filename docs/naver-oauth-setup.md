# 네이버 스마트스토어 간편 연결 설정

네이버 커머스ID 팝업 인증 → 판매자·요금제 확인 → 필요한 경우 솔루션 사용 승인 → 워크스페이스 연결을 구현했다. 기존 앱 ID·시크릿 입력 연결도 유지한다. 일반 네이버 로그인이나 `authorization_code` 토큰 교환 API가 아니다. 최종 커머스 API 토큰은 기존 `client_credentials` 방식으로 발급한다.

**현재 솔루션마켓 미등록 상태이므로 기본 비활성화다.** 등록·공식 팝업 계약·운영 설정을 검증하기 전에는 capabilities가 `ready: false`를 반환하고 화면에 준비 중 안내를 표시한다. 실제 판매자 팝업 인증·구독 승인·과금은 검증하지 않았다.

등록 전에는 [본인 스토어 테스트 연결](naver-connections.md#등록-전-본인-스토어-테스트)을 사용할 수 있다. 로컬 1번 워크스페이스의 소유자가 저장된 앱 정보로 직접 연결하며, 이 SELF 연결의 실제 토큰 발급과 채널 조회는 확인했다. 이 문서의 판매자 팝업 인증·구독 승인과 별개이며 나중에 같은 연결을 전환할 수 있다.

## 적용 순서

1. 기존 `20260921_naver_platform_integration.sql` 적용을 확인한다.
2. 대상 DB 백업 후 `db/migrations/20260923_naver_oauth_core.sql`, `20260923_naver_solution_connections.sql` 순서로 같은 SQL 연결에서 각각 전체 실행한다. 애플리케이션은 SQL을 자동 실행하지 않는다. 기존 연결은 `MANUAL`로 유지한다.
3. 네이버 커머스솔루션마켓 등록과 커머스ID 인증 옵션 설정을 완료하고, 발급받은 공식 팝업 실행 URL·콜백 매개변수·state 반환 규격을 확인한다. 공개 문서에 없는 URL을 추측해서 채우지 않는다.
4. 아래 서버 설정을 비밀 저장소나 서버 환경변수로 주입한다. 프런트엔드 `VITE_*` 변수에는 시크릿·JWT 키·웹훅 키를 넣지 않는다.
5. 동일한 HTTPS 도메인에서 프런트엔드와 `/api`, `/open-api`를 제공한다. 등록된 테스트 판매자로 시작·취소·승인·재연결·해지·재신청을 검증한 후 기능을 활성화한다.

일반 `http://localhost:3400`은 실제 간편 연결 대상이 아니다. 개발 화면의 모의 테스트는 가능하지만, 실제 인증은 등록된 HTTPS 도메인과 Secure 쿠키가 필요하다. 프록시는 인증 POST의 `Origin`을 그대로 전달해야 한다.

## 서버 설정

설정 이름은 `naver.solution.*`이다. [설정 예시](naver-solution.example.yml)는 기본 비활성화이며 실제 자격 증명을 포함하지 않는다.

`application-local.yml`에 이미 입력한 `app.naver-commerce.app-id`와 `app.naver-commerce.app-secret`은 공통 설정에서 각각 `naver.solution.client-id`와 `client-secret`으로 연결된다. 시크릿을 다른 설정이나 프런트에 복사할 필요가 없다. 키 이름은 **`naver-commerce`**이며 `naver-commers`는 사용하지 않는다. `naver.solution.client-id/client-secret`을 프로파일 설정이나 환경변수에 별도로 지정하면 그 값이 우선하므로 다른 앱으로 바꿀 때는 두 값을 함께 설정한다.

앱 정보만 입력하면 서버 자격 증명 연결까지만 완료된다. 판매자 팝업 인증에는 아래 솔루션 등록 정보가 추가로 필요하며, 앱 정보를 입력했다고 `enabled`나 `provider-contract-verified`를 자동으로 켜지 않는다. 내스토어(`SELF`) 앱으로 외부 판매자의 솔루션 구독 인증을 대신할 수 없다.

| 설정 | 값 |
| --- | --- |
| `enabled` | 배포 시 명시적으로 활성화. 기본 `false` |
| `provider-contract-verified` | 공식 팝업과 state 반환 계약 확인 후 `true` |
| `application-ref` | 앱을 구분하는 안정적인 서버 식별자 |
| `solution-id` | 네이버 등록 솔루션 ID |
| `client-id`, `client-secret` | 기본값은 `app.naver-commerce.app-id/app-secret`. 솔루션 앱 자격 증명이며 시크릿은 발급된 BCrypt 형식 |
| `credential-version` | 앱 시크릿 교체 시 증가시키는 양의 정수 |
| `jwt-public-key` | 해당 앱의 RS256 공개키 PEM |
| `public-base-url`, `frontend-base-url` | 동일한 HTTPS origin. 경로·끝 슬래시 없이 설정 |
| `marketplace-url` | 등록된 솔루션의 네이버 마켓 주소 |
| `authorization-url-template` | 공식 팝업 URL. 쿼리 값에 `{state}`, `{callback}` 필수, `{solutionId}` 선택. 네이버 HTTPS 도메인만 허용 |
| `callback-state-parameter`, `callback-proof-parameter` | 네이버에서 확인한 콜백의 state·JWE 매개변수명 |
| `marketplace-token-parameter` | 마켓 유입 시 JWT 매개변수명 |
| `webhook-header-name`, `webhook-key` | 네이버에 등록한 사용자 정의 헤더와 32자 이상 인증 키 |

네이버에 등록할 URL은 아래 세 가지다. 프록시·애플리케이션에서 콜백 쿼리, 쿠키, Authorization, JWE/JWT 원문을 로그에 남기지 않도록 설정한다. DB의 일시적인 증명과 저장된 토큰은 기존 `aes.key.personal-data-key`로 암호화한다.

- JWE 콜백: `https://등록도메인/open-api/integrations/naver/callback`
- 마켓 유입: `https://등록도메인/open-api/integrations/naver/marketplace`
- 이벤트 훅: `https://등록도메인/open-api/integrations/naver/events`

## 서비스 API

공통 경로는 `/api/workspaces/{workspaceId}/connections/naver`다. 서비스 Access Token을 사용하며 응답은 `Api.body`, JSON 필드는 snake_case다. capabilities는 멤버 조회, 인증 변경은 워크스페이스 소유자만 가능하다.

| 메서드·경로 | 동작 |
| --- | --- |
| `GET /capabilities` | 준비 여부, 이유, 수동 연결 허용 여부 |
| `POST /authorizations` | 시도 생성. 선택 필드 `reconnect_connection_id`, `marketplace_receipt` |
| `GET /authorizations/{attemptId}` | 상태·판매자·요금제·검토 버전·연결 결과 조회 |
| `POST /authorizations/{attemptId}/launch-ticket` | 최초 브라우저에서 만료된 팝업 티켓 재발급 |
| `POST /authorizations/{attemptId}/cancel` | 승인 시작 전 취소 |
| `POST /authorizations/{attemptId}/complete` | `{ "review_revision": 1 }`와 `Idempotency-Key`로 검토한 연결 확정 |

콜백 화면은 `GET /api/integrations/naver/authorizations/{attemptId}`로 워크스페이스와 상태를 조회한다. 보호된 POST에는 정확한 동일 출처 `Origin`이 필요하고 complete·cancel·재발급에는 시작 브라우저의 HttpOnly 쿠키도 필요하다. 브라우저는 승인 POST를 인증 갱신 후 자동 재전송하지 않는다. 불확실한 결과는 GET으로 확인한다.

공개 launch는 60초 일회용 티켓과 최초 브라우저를 검증한다. 인증 검토는 10분이며, 원문 증명은 완료·취소·만료 시 제거한다. 마켓 JWT 유입은 서비스 로그인과 소유 워크스페이스 선택 후 별도 커머스ID 인증으로 이어진다. 마켓 JWT만으로 연결을 저장하지 않는다.

기존 수동 연결은 해당 행의 **네이버 간편 연결로 전환**으로 시작한다. 같은 판매자 UID만 허용하며 기존 연결 ID와 저장한 채널을 유지한다. 새 연결 버튼으로 기존 수동 연결을 암묵적으로 덮어쓰지 않는다. 목록 응답의 `connection_mode`는 `MANUAL` 또는 `SOLUTION`, `connection_status`는 `CONNECTED` 또는 `REAUTH_REQUIRED`다.

## 승인과 접근 철회

승인이 필요한 경우 화면에서 스토어·워크스페이스·요금제와 비용 안내를 확인하고 명시적으로 동의한다. 공식 조회 스키마로 확인하지 못한 금액을 무료로 표시하지 않는다. 검토 후 신청·요금제가 바뀌면 새 검토 버전으로 다시 확인한다. 승인 작업은 판매자와 구독 수명별로 저장하고, 결과가 불확실하면 승인 요청을 재전송하지 않고 사용 상태를 조회한다.

브라우저를 닫아도 확정된 진행 중 시도는 서버가 상태 조회와 로컬 연결 저장을 이어간다. 한 번에 20개, 시도별 최대 20회로 제한하며 조회 간격을 늘린다. 소유권을 잃으면 자동 확인을 중단한다. 한도 이후에는 소유자가 상태 화면에서 직접 조회할 수 있다. 이 작업자는 승인 API를 호출하지 않는다.

`SOLUTION` 연결에는 앱 시크릿을 복제하지 않고 서버 앱 참조, 구독 ID와 세대를 저장한다. 네이버 채널 조회와 토큰 갱신은 현재 구독 및 버전을 다시 확인한다. 해지 후 재신청은 새 수명으로 처리하여 이전 워크스페이스 권한이 자동으로 복구되지 않는다.

웹훅은 등록한 헤더 인증과 `solutionId`를 검사하고 `(solutionId, eventId, changeType)`으로 중복을 구분한다. 수신 즉시 해당 구독을 확인 필요 상태로 두고 작업자가 사용 상태를 재조회한다. 확정되지 않은 상태에서는 접근을 허용하지 않는다. 이전 수명의 명시적인 `accountMappingId` 이벤트는 새 연결에 적용하지 않는다. 해지·미확인 상태에서는 해당 세대의 SOLUTION 토큰을 제거하고 재연결 필요로 표시한다. 수동 연결에는 적용하지 않는다.

## 운영 활성화 전 남은 확인

솔루션 등록 후 공식 팝업의 실제 URL과 state 반환 방식, JWT 권한값, `accountAuthentication`·`approveSubscriptionYn`의 실제 상태별 조합, 구독 수명 식별자 및 요금제 값을 테스트 계정으로 확인해야 한다. 현재는 공개 공식 스키마와 모의 응답을 기준으로 구현했다. 등록된 팝업이 state 반환을 지원하지 않는 경우 이 템플릿만으로 활성화하지 말고 공급자 계약에 맞는 바인딩 방식을 추가한다.

이 변경은 연결 기능 범위다. 유료 솔루션 출시에는 네이버의 결제·환불 및 표시 요구사항과 해지 시 개인정보·식별자 삭제 정책을 별도로 완성해야 한다. 현재 철회 처리는 토큰 제거와 접근 차단을 수행하며 저장된 채널 이름·UID 등의 완전 삭제까지 수행하지 않는다.

공식 근거: [커머스 API 인증](https://apicenter.commerce.naver.com/docs/auth), [커머스ID 인증](https://apicenter.commerce.naver.com/docs/solution-doc/3000/판매자-커머스-아이디-인증), [JWE 해석](https://apicenter.commerce.naver.com/docs/commerce-api/current/get-seller-info-by-token-merchant), [사용 승인](https://apicenter.commerce.naver.com/docs/commerce-api/current/approve-subscription-merchant), [사용 상태 조회](https://apicenter.commerce.naver.com/docs/commerce-api/current/get-subscription-merchant), [심사 전 체크리스트](https://apicenter.commerce.naver.com/docs/solution-doc/5000/심사-전-체크리스트).

## 검증

- 프런트엔드 `npm run build`, `npm run lint`; API를 모킹한 브라우저 시나리오 24개 통과. 준비 중·수동 연결·소유자 제어·팝업·검토·중복 확정·401·불확실 결과·로그인 복귀·마켓 진입·모바일 화면을 검증했다.
- 백엔드 `./gradlew --offline :api:test :db:test`; 실제 외부 승인 없이 모의 HTTP, 서비스·MVC, H2 영속성 테스트를 실행한다. JWT 서명, 콜백 바인딩, 중복 승인, 경합, 해지, 증명 암호화를 검증한다.
- 네트워크를 차단한 임시 MySQL 8.4에서 신규 SQL을 두 번 적용하여 재실행, 수동 연결 보존, 전환 시 채널 유지, signed/unsigned 외래 키, `SET NULL`, 잘못된 자격 증명 조합 거부를 확인했다. 사용자 DB에는 적용하지 않았다.
