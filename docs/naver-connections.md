# 네이버 스마트스토어 연결

네이버 커머스 API의 앱 ID·시크릿으로 판매자를 인증하고 워크스페이스에 연결한다. 구현 범위는 연결·재연결, 스마트스토어 채널 조회·선택 저장, 저장된 토큰의 재발급이다. 네이버 검색광고, 상품·주문 처리, 커머스솔루션 구독 승인 API는 포함하지 않는다.

## 인증 및 준비

네이버 커머스API센터에서 발급한 애플리케이션 자격 증명을 사용한다. 일반 네이버 로그인용 클라이언트 ID·시크릿과 다르다. 서버의 외부 통신 IP를 애플리케이션의 허용 IP로 등록하고, 판매자 계정·채널 조회에 필요한 권한을 설정한다. [공식 인증 안내](https://apicenter.commerce.naver.com/docs/auth)

- `SELF`: 판매자의 내스토어 애플리케이션. `account_id`를 보내지 않는다.
- `SELLER`: 대상 판매자에 대한 권한을 가진 애플리케이션. 판매자 ID 또는 UID인 `account_id`가 필요하다. 솔루션 앱은 해당 판매자의 구독·승인 등 네이버 요구사항을 충족해야 한다.

토큰은 `client_credentials` 방식으로 발급한다. 서버가 `client_id_timestamp`를 발급된 시크릿으로 BCrypt 처리한 뒤 Base64 서명을 생성한다. 이 연결에는 브라우저 OAuth 콜백이나 프런트엔드 리다이렉트 설정이 필요하지 않다. [공식 토큰 발급 API](https://apicenter.commerce.naver.com/docs/commerce-api/current/exchange-sellers-auth)

ID·시크릿은 워크스페이스마다 연결 요청으로 받아 저장한다. 따라서 `application-local.yml`·`application-prod.yml`에 공통 네이버 계정을 추가하지 않는다. DB 암호화에는 기존 `aes.key.personal-data-key`를 사용한다. 시크릿은 네이버에서 받은 BCrypt salt 형식 그대로 전달한다. 잘못된 값이나 과도한 BCrypt 비용(허용 범위 4~14)은 서버에서 거부한다.

## 권한과 저장 구조

연결·재연결은 워크스페이스 소유자만 가능하다. 참여한 멤버는 별도 네이버 로그인 없이 같은 워크스페이스의 저장된 토큰으로 채널 조회·선택을 사용할 수 있다. 초대 대기·추방된 사용자는 접근할 수 없고, 요청 경로와 다른 워크스페이스의 연결 ID도 거부한다. 외부 요청 이후와 저장 직전에 멤버 권한을 다시 확인한다.

| 테이블 | 정보 |
| --- | --- |
| `platform_connections` | 기존 공통 연결. `provider_type = NAVER`, `external_account_id`는 네이버가 검증한 `accountUid` |
| `naver_connections` | 공통 연결 ID를 PK/FK로 공유. client ID, 암호화된 시크릿·토큰, 인증 유형, SELLER 대상 ID, 토큰 만료 시각 |
| `platform_assets` | `platform_type = NAVER_SMART_STORE`, `asset_type = STORE`, `external_id`는 채널 번호의 문자열 |
| `naver_assets` | 공통 자산 ID를 PK/FK로 공유. 네이버 채널 유형·주소 |

토큰과 시크릿 원문은 응답에 포함하지 않는다. 같은 판매자를 같은 워크스페이스에 다시 연결하면 연결 ID를 유지하고 자격 증명만 갱신한다. 다른 워크스페이스에 같은 판매자를 연결할 수도 있다. 연결 소유권은 네이버에서 확인한 판매자 UID로 판단하며, 요청자가 보낸 이름·채널 정보를 신뢰하지 않는다.

배포 전에 `db/migrations/20260921_naver_platform_integration.sql`을 같은 SQL 연결에서 전체 스크립트로 실행한다. 기존 Meta 공통 구조 마이그레이션 이후에 실행하며, 오류 시 중단한다. 네이버 확장 테이블과 `STORE` 자산 유형을 추가하고 기존 `connection_id` NULL 허용·외래 키 `SET NULL` 정책은 유지한다. `BIGINT UNSIGNED`로 생성된 부모 ID도 기존 타입에 맞춰 외래 키를 생성한다. 스크립트는 재실행할 수 있고 애플리케이션에서 자동 실행하지 않는다.

## API

모든 요청은 서비스의 Access Token 인증이 필요하고 요청·응답 JSON은 snake_case다. 아래 응답은 기존 `Api`의 `body` 내부 구조다.

| 메서드 | 경로 | 권한·기능 |
| --- | --- | --- |
| POST | `/api/workspaces/{workspaceId}/connections/naver` | 소유자: 네이버 계정 연결·재연결 |
| GET | `/api/workspaces/{workspaceId}/connections` | 소유자·멤버: 기존 공통 API에서 Meta와 네이버 연결 및 저장한 자산 조회 |
| GET | `/api/workspaces/{workspaceId}/connections/{connectionId}/naver/channels` | 소유자·멤버: 사용 가능한 스마트스토어 채널 조회 |
| POST | `/api/workspaces/{workspaceId}/connections/{connectionId}/naver/channels` | 소유자·멤버: 사용할 채널 선택·저장 |

내스토어 연결 요청:

```json
{
  "client_id": "발급받은 애플리케이션 ID",
  "client_secret": "발급받은 애플리케이션 시크릿",
  "token_type": "SELF"
}
```

`token_type`을 생략하면 `SELF`다. `SELLER`는 아래처럼 대상 판매자를 함께 보낸다.

```json
{
  "client_id": "권한을 가진 애플리케이션 ID",
  "client_secret": "발급받은 애플리케이션 시크릿",
  "token_type": "SELLER",
  "account_id": "대상 판매자 ID 또는 UID"
}
```

연결 응답의 `id`를 이후 경로의 `connectionId`로 사용한다. 채널 선택 전까지 연결 응답의 `assets`는 비어 있을 수 있다. 예시 채널 조회 응답:

```json
[
  {
    "channel_no": 123456789,
    "channel_type": "STOREFARM",
    "name": "우리 스마트스토어",
    "url": "https://smartstore.naver.com/example"
  }
]
```

네이버 원본 응답의 단일 객체를 이 API에서는 목록으로 반환한다. 스마트스토어인 `STOREFARM`만 사용하며 쇼핑윈도인 `WINDOW`는 제외한다. [네이버 채널 API](https://apicenter.commerce.naver.com/docs/commerce-api/current/get-channels-by-account-no-sellers)

채널 선택 요청:

```json
{
  "channel_nos": [123456789]
}
```

최대 100개를 요청할 수 있다. 중복 입력은 한 번만 처리하고, 이미 선택한 채널은 기존 행을 갱신한다. 선택 시 네이버에서 채널 목록을 다시 확인하며 접근할 수 없는 채널이 하나라도 포함되면 전체 선택을 저장하지 않는다. 기존에 선택한 다른 채널은 유지한다. 반환값에는 저장된 `asset_id`도 포함된다.

## 토큰 갱신과 실패 처리

토큰의 `expires_in`을 기준으로 만료 시각을 저장한다. 채널 사용 시 만료까지 1분 이하로 남았으면 저장한 앱 자격 증명으로 새 토큰을 발급받는다. 갱신 후 판매자 UID가 기존 연결과 일치하는지도 확인한다. 네이버가 `401 / GW.AUTHN`을 반환하면 한 번만 재발급·재시도하며, 반복 인증 실패나 판매자 변경은 재연결 필요 상태로 표시한다. 권한 거절·호출 제한·서버 오류는 무조건 반복 호출하지 않는다.

조회·갱신 도중 소유자가 자격 증명을 바꾸면 이전 요청이 새 연결을 덮어쓰지 못하도록 검사한다. 만료된 네이버 토큰 자체는 자동 재발급할 수 있으므로 공통 목록의 `requires_reauth`를 즉시 true로 만들지 않는다. 저장된 확장 정보가 없거나 재연결 필요 상태면 소유자가 연결 API를 다시 호출해야 한다.

테스트에서는 모의 HTTP로 인증 서명·공식 응답 형식·갱신·재시도·권한과 디버그 로그의 시크릿·토큰 비노출을 검증하고 H2에서 실제 암호화 저장/복호화를 확인한다. SQL은 별도 MySQL 8.4에서 재실행, 부모 ID의 signed/unsigned 타입, 기존 ENUM 값 보존, `SET NULL` 동작을 검증했다. 실제 네이버 계정과 허용 IP 설정은 운영 자격 증명으로 별도 확인해야 한다. 구현 중 실제 판매자 계정에 연결하거나 상품·주문을 변경하지 않는다.
