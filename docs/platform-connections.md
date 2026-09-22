# 플랫폼 연결

현재 Meta OAuth와 네이버 스마트스토어 연결을 지원한다. 아래는 Meta의 광고 계정·Facebook 페이지·연결된 Instagram 프로필 조회 및 자산 선택 방법이다. 네이버 인증·채널 조회·선택·토큰 갱신은 [네이버 연결 문서](naver-connections.md)를 참고한다. 광고 생성·집행, 게시물 발행, Threads·쿠팡 연결은 후속 구현 범위다.

## DB 구조

플랫폼을 추가할 때 공통 테이블을 유지하고 해당 플랫폼의 확장 테이블을 추가한다. JPA 상속이나 공통 OAuth 인터페이스는 두지 않았다. 쿠팡처럼 OAuth가 아닌 인증도 확장 테이블에서 처리할 수 있다.

| 테이블 | 저장 정보 |
| --- | --- |
| `platform_connections` | 워크스페이스, 제공자, 외부 계정 ID·이름, 재인증 필요 여부, 생성·수정 시각 |
| `meta_connections` | 공통 연결 ID를 PK/FK로 사용, 암호화된 사용자 토큰, 만료 시각, 실제 승인된 권한 |
| `platform_assets` | 워크스페이스·연결 ID, 플랫폼, 자산 유형, 외부 자산 ID·이름 |
| `meta_assets` | 공통 자산 ID를 PK/FK로 사용, Facebook 페이지 ID. Instagram 프로필의 부모 페이지도 여기 저장 |

연결은 `(workspace_id, provider_type, external_account_id)`로 중복을 방지한다. 같은 Meta 계정을 여러 워크스페이스에 연결할 수 있다. 같은 워크스페이스에서 재인증하면 기존 연결 ID와 선택한 자산을 유지하면서 토큰을 갱신한다. 자산은 `(connection_id, platform_type, asset_type, external_id)`로 중복을 방지한다.

`platform_assets.connection_id`는 연결 삭제 후 자산 기록을 보존할 수 있도록 NULL을 허용하며, 기존 외래 키의 `SET NULL` 정책을 유지한다. 연결이 없는 과거 자산은 연결별 조회 결과에 포함하지 않는다. 자산 선택 API는 워크스페이스에 속한 유효한 연결을 확인하고 연결 ID를 지정해 저장한다. 계정의 워크스페이스 귀속과 토큰 확장 테이블의 필수 연결 ID에는 영향이 없다.

계정 연결과 토큰은 개인 서비스 사용자 ID가 아닌 워크스페이스에 귀속된다. 소유자가 한 번 연결하면 참여를 완료한 멤버 모두 별도 Meta 로그인 없이 워크스페이스의 연결과 자산을 사용할 수 있다. 서버는 워크스페이스 소속을 확인한 뒤 `meta_connections`에 암호화해 저장한 토큰으로 Meta API를 호출한다. 멤버별 토큰 사본은 만들지 않으며 API 응답에 토큰을 포함하지 않는다. 외부 Meta에서 발급한 토큰의 만료·승인 권한 조건은 그대로 적용된다.

기존 테이블이 있는 환경에서는 배포 전에 `db/migrations/20260921_meta_platform_integration.sql`을 MySQL 클라이언트로 실행한다. 스크립트는 기존 중복·불완전한 자산 데이터가 있으면 중단한다. 기존 공통 테이블의 토큰 컬럼은 삭제하지 않고 nullable로 보존하며, 코드에서는 더 이상 읽거나 쓰지 않는다. 기존 Meta 연결은 재인증해야 암호화된 확장 레코드가 생성된다. 새 DB는 Hibernate의 스키마 생성 또는 별도 스키마 배포가 필요하다. 이 SQL은 기존 공통 테이블이 있다는 전제이며 애플리케이션에서 자동 실행하지 않는다.

SQL 도구에서는 대상 DB를 선택하고 **전체 파일을 같은 연결에서 스크립트 실행**한다. 구분자는 기본 `;`를 사용하고 오류가 발생하면 중단하도록 설정한다. 프로시저와 `DELIMITER`는 사용하지 않는다. 사전 검사는 임시 테이블을 사용하므로 `CREATE TEMPORARY TABLES` 권한이 필요하다. 검사 중 1062 오류가 나면 메시지의 `platform_assets_has_null_required_fields`, `platform_connections_has_duplicate_accounts`, `platform_assets_has_duplicate_external_ids` 중 해당 항목의 기존 데이터를 정리한 뒤 처음부터 다시 실행한다. 이 검사에서 오류가 나면 이후 DDL을 계속 실행하지 않는다.

## 설정

`application-local.yml`, `application-prod.yml`의 기존 `app.meta.app-id`, `app.meta.app-secret`, `app.meta.redirect-uri`를 사용한다. 토큰 암호화는 기존 `aes.key.personal-data-key`를 사용한다. Redis는 일회용 OAuth 요청을 10분간 저장하므로 Redis 6.2 이상(`GETDEL` 지원)이 필요하다.

| 환경변수 | 용도 |
| --- | --- |
| `META_APP_ID`, `META_APP_SECRET` | 운영 프로필의 Meta 앱 자격 증명. 로컬은 기존 설정값 유지 |
| `META_REDIRECT_URI` | 백엔드의 `/open-api/platform-connections/meta/callback` 전체 URL. Meta 개발자 콘솔에 동일한 URI를 등록 |
| `META_FRONTEND_REDIRECT_URI` | 연결 완료 후 돌아갈 프런트엔드 페이지의 전체 URL |
| `META_API_VERSION` | Graph/Marketing API 버전. 기본 `v26.0` |

로컬 기본 콜백은 `http://localhost:8480/open-api/platform-connections/meta/callback`, 완료 페이지는 `http://localhost:3100/settings/integrations/meta/callback`이다. 프런트엔드의 실제 라우트와 실행 포트에 맞게 환경변수로 변경한다. 공개 배포 주소는 HTTPS를 사용한다. 운영 완료 페이지는 기본값이 없으며 반드시 지정한다. 프런트엔드 완료 페이지 자체는 이 백엔드 변경에 포함되지 않는다.

프런트엔드와 API는 **같은 사이트**에서 제공해야 한다. 예: `app.example.com`과 `api.example.com`, 또는 localhost의 서로 다른 포트. 연결 시작 요청은 `credentials: 'include'`로 보내고, 요청에 사용한 API 호스트와 `META_REDIRECT_URI` 호스트를 일치시킨다. localhost 프런트엔드에서 ngrok API로 직접 호출하는 구성은 상태 쿠키가 차단될 수 있으므로 같은 사이트 프록시 또는 프런트엔드·API를 함께 노출하는 구성을 사용한다. CORS에도 실제 프런트엔드 origin이 허용되어 있어야 한다.

Meta 앱은 Facebook Login과 Marketing API를 사용한다. 요청 권한은 `ads_read`, `ads_management`, `pages_show_list`, `pages_read_engagement`, `instagram_basic`이며 모두 승인되어야 연결을 저장한다. 다른 고객의 계정을 운영 환경에서 연결하려면 해당 권한의 앱 검수·접근 수준 등 Meta 요구사항을 충족해야 한다. Instagram은 Facebook 페이지에 연결된 프로페셔널 계정이 대상이다. 개인 Instagram 계정이나 페이지와 연결되지 않은 계정은 이 방식의 조회 대상이 아니다.

## API

콜백 이외의 API는 기존 Access Token 인증을 사용한다. 연결 목록·Meta 자산 조회·자산 선택 저장은 워크스페이스 소유자와 참여한 멤버 모두 가능하다. 계정 연결 시작과 재인증은 소유자만 가능하다. 초대 대기 중이거나 추방된 사용자는 사용할 수 없으며, 다른 워크스페이스의 연결 ID로 접근할 수도 없다. 외부 자산 조회가 끝난 후와 자산 저장 직전에 멤버 권한을 다시 확인한다. JSON은 기존 전역 설정대로 snake_case다.

| 동작 | 소유자 | 참여한 멤버 |
| --- | --- | --- |
| 계정 연결·재인증 | 가능 | 불가 |
| 연결 및 저장된 자산 목록 조회 | 가능 | 가능 |
| 저장된 토큰을 통한 Meta 자산 조회 | 가능 | 가능 |
| 사용할 자산 선택·저장 | 가능 | 가능 |
| 토큰 원문 조회 | 제공하지 않음 | 제공하지 않음 |

| 메서드 | 경로 | 기능 |
| --- | --- | --- |
| POST | `/api/workspaces/{workspaceId}/connections/meta/authorize` | OAuth URL 발급, 브라우저 상태 쿠키 설정 |
| GET | `/open-api/platform-connections/meta/callback` | Meta 인증 결과 처리 후 고정된 프런트엔드 주소로 이동 |
| GET | `/api/workspaces/{workspaceId}/connections` | 연결 목록 및 이미 저장한 자산 조회 |
| GET | `/api/workspaces/{workspaceId}/connections/{connectionId}/meta/assets` | 현재 Meta에서 접근 가능한 자산 전체 조회 |
| POST | `/api/workspaces/{workspaceId}/connections/{connectionId}/meta/assets` | 선택한 자산 추가·갱신. 최대 100개, 기존 선택은 유지 |

### 연결 흐름

1. 프런트엔드가 인증 헤더와 쿠키 수신 옵션을 포함해 연결 시작 API를 호출한다. 요청 본문은 없다.
2. `body.authorization_url`로 같은 브라우저를 이동한다. 응답의 `expires_in_seconds`는 600이다.
3. 사용자가 Meta에서 동의하면 백엔드 콜백이 브라우저 쿠키와 일회용 state를 확인하고 코드 교환을 수행한다. 소유자 권한과 활성 사용자 상태를 다시 확인한 뒤 연결을 저장한다.
4. 프런트엔드 완료 URL에 성공 시 `status=success&workspace_id=...&connection_id=...`, 실패 시 `status=error&error_code=...`를 붙인다. 토큰·인증 코드는 프런트엔드로 전달하지 않는다. 실패하면 연결 시작부터 다시 시도한다.
5. 프런트엔드에서 연결 목록과 사용 가능한 자산을 조회하고 사용할 자산을 선택한다.

```javascript
const response = await fetch(`${apiBase}/api/workspaces/${workspaceId}/connections/meta/authorize`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${accessToken}` },
  credentials: 'include',
});
if (!response.ok) throw new Error('Meta 연결 요청 실패');
const { body } = await response.json();
window.location.assign(body.authorization_url);
```

자산 선택 요청 예시:

```json
{
  "assets": [
    { "external_id": "act_123456", "platform_type": "FACEBOOK", "asset_type": "AD_ACCOUNT" },
    { "external_id": "17841400000000000", "platform_type": "INSTAGRAM", "asset_type": "PROFILE" }
  ]
}
```

자산 ID는 조회 결과를 그대로 사용한다. 광고 계정은 Meta가 반환한 `act_...` 형식을 유지한다. 저장 직전에 Meta에서 접근 가능한 목록을 다시 조회하며, 이름과 페이지 연결 정보는 서버 응답으로 채운다. 요청 중 하나라도 접근할 수 없는 자산이면 전체 선택을 저장하지 않는다. 같은 자산을 다시 선택하면 기존 행을 갱신한다. 조회 도중 재인증으로 토큰이 변경되면 다시 조회하도록 요청한다.

만료된 토큰이나 확장 정보가 없는 기존 Meta 연결은 목록에서 `requires_reauth: true`로 표시된다. 토큰을 자동 연장하는 작업은 없으며, 같은 계정으로 연결 시작 API를 다시 호출해 갱신한다. 저장된 자산은 선택 이력이므로 향후 광고 실행 시에는 Meta의 현재 권한을 다시 확인해야 한다.

## 검증 범위

H2에서 확장 테이블 매핑·중복 제약·실제 AES-GCM 저장/복호화를 검증하고, 모의 HTTP로 OAuth 교환·권한·페이지 순회·오류 처리를 검증한다. 실제 Meta 앱 검수 상태, 계정 자산, 브라우저 리다이렉트는 배포 설정 후 별도로 확인해야 한다. 실제 Meta 계정 요청이나 광고 집행은 테스트에서 수행하지 않는다.

마이그레이션 SQL은 격리된 MySQL 8.4에서 기존 스키마 적용, 재실행, 구 토큰 컬럼이 없는 스키마 적용, 기존 데이터 보존을 검증했다. 기존 외래 키의 `ON DELETE SET NULL`, `ON UPDATE SET NULL`, `RESTRICT` 정책 및 연결이 없는 과거 자산도 보존되는 것을 확인했다. NULL 필수 필드·중복 연결·연결된 자산의 중복은 strict SQL mode가 꺼진 경우에도 DDL 전에 차단된다. 운영 DB에는 실행하지 않았다.

참고: [Meta API 버전 안내](https://developers.meta.com/blog/), [Meta 공식 Instagram API 문서](https://www.postman.com/meta/instagram/documentation/6yqw8pt/instagram-api), [Meta Business SDK의 인증 처리](https://github.com/facebook/facebook-python-business-sdk/blob/main/facebook_business/session.py).
