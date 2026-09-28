# 관리자 API

`admin-api`는 일반 서비스 `api`와 같은 DB를 사용하는 별도 Spring Boot 애플리케이션이다. 기본 포트는 `8481`, 공통 경로는 `/admin-api`다. 활성 관리자(`users.status=REGISTERED`, `users.role=ADMIN`)만 사용할 수 있다. 브라우저 화면은 `http://localhost:3400/admin/login`에서 열며, [로컬 로그인 상세 안내](admin-local-login.md)에 계정 준비와 실행 방법을 정리했다.

## 코드 구조

`api` 모듈과 같은 도메인 계층을 사용한다. 자세한 패키지 구성은 [admin-api README](../admin-api/README.md)를 참고한다.

| 위치 | 역할 |
| --- | --- |
| `domain/{domain}/controller` | HTTP 경로, 요청 검증, `Api` 응답 |
| `domain/{domain}/controller/model` | 개별 요청·응답 DTO |
| `domain/{domain}/business` | 업무 규칙, 여러 Service 조합, 트랜잭션 경계 |
| `domain/{domain}/service` | DB 조회·수정 작업, 공통 관리 권한 검사 |
| `domain/{domain}/converter` | 조회 결과·Entity를 응답 DTO로 변환 |
| `domain/token/helper` | 관리자 JWT 발급·검증 |
| `common/api`, `common/exception`, `exceptionhandler` | 공통 응답, 페이지 처리, 예외 응답 |
| `config/jpa`, `config/objectmapper`, `config/security` | 영속성, JSON, 인증·접근 제어 설정 |
| `db` 모듈 | Entity, Repository, 조회용 projection |

관리자 모듈이 일반 `api` 실행 모듈에 의존하지는 않는다. 두 서버는 `db` 모듈을 공유한다. 구조 변경 전의 `/admin-api` 경로, JSON 필드, 페이지 응답 및 관리자 인증 규칙은 유지한다.

감사 및 기술 지원 Entity도 `db` 모듈에 있으므로 두 서버의 JPA 스키마 검증 대상에 포함된다. 아래 마이그레이션을 두 서버 실행 전에 적용해야 한다. 도메인 계층 정리 자체는 별도 SQL을 요구하지 않지만, 기술 지원 기능에는 지원 테이블이 필요하다.

## 실행과 적용 순서

1. 기존 DB에 [20260928_admin_operations.sql](../db/migrations/20260928_admin_operations.sql)을 한 번 적용한다. `users.auth_version`, `SUSPENDED` 상태, `admin_audit_logs` 테이블이 추가된다. 이어서 [20260928_support_sessions.sql](../db/migrations/20260928_support_sessions.sql)을 한 번 적용한다. 이미 적용한 SQL은 다시 실행하지 않는다.
2. 일반 `api`와 `admin-api`를 함께 새 버전으로 배포한다. 일반 API도 인증 버전을 검사해야 세션 강제 종료가 적용된다. 이전 API 인스턴스가 모두 교체되기 전에는 관리자 변경 기능을 사용하지 않는다.
3. 기존 활성 회원 중 운영자로 확인한 계정에 최초 `ADMIN` 권한을 부여한다. 초기 관리자 자동 생성이나 공개 관리자 가입 API는 없다.
4. `POST /admin-api/auth/login`으로 관리자 토큰을 발급받는다.

마이그레이션은 자동 실행되지 않는다. MySQL DDL은 암묵적으로 커밋되므로 배포 전에 DB 변경 절차에 따라 적용한다. Hibernate의 MySQL 문자열 enum 매핑에 맞춰 기존 `status` enum에 `SUSPENDED`를 추가한다. [Hibernate 공식 매핑 변경 안내](https://docs.hibernate.org/orm/6.2/migration-guide/#ddl-implicit-datatype-enum)

최초 관리자 지정은 신뢰할 수 있는 DB 운영자가 수행한다. 아래의 `123`과 이메일을 **확인한 기존 회원 정보로 교체**한다. 영향받은 회원이 정확히 1명인지 확인한 뒤 커밋하며, 다르면 롤백한다. 이 SQL은 서비스에서 실행하지 않는다.

```sql
START TRANSACTION;
SELECT id, email, status, role FROM users WHERE id = 123 FOR UPDATE;
UPDATE users
SET role = 'ADMIN', auth_version = auth_version + 1,
    updated_at = CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')
WHERE id = 123 AND email = 'operator@example.com'
  AND status = 'REGISTERED' AND role = 'CUSTOMER';
SELECT ROW_COUNT() AS changed_users;
-- changed_users = 1 확인 후에만 이어서 실행
UPDATE tokens SET status = 'EXPIRED',
    revoked_at = CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')
WHERE user_id = 123 AND status = 'ACTIVE';
COMMIT;
```

그 이후 관리자는 기존 관리자 계정으로 권한 변경 API를 사용한다. 최초 DB 지정 작업은 외부 운영 기록에 남기며, 이후 API 변경 작업은 감사 이력에 저장된다.

```sh
./gradlew :admin-api:bootRun
# 운영 프로필
SPRING_PROFILES_ACTIVE=prod ./gradlew :admin-api:bootRun
```

로컬 실행은 기존 `application-local.yml`의 DB·암호화·토큰 설정을 사용한다. 이 파일은 실행 JAR에서 제외한다. 운영 환경은 다음 값을 설정한다.

| 환경 변수 | 용도 |
| --- | --- |
| `DB_HOST`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | 일반 API와 공유하는 DB |
| `DB_PORT` | 기본 `3306` |
| `DB_SSL_MODE` | 기본 `PREFERRED`, 배포 DB에 맞게 지정 |
| `AES_PERSONAL_DATA_KEY`, `AES_SEARCH_HMAC_KEY` | 일반 API와 같은 암호화 설정 |
| `TOKEN_SECRET_KEY` | JWT 서명 키. 기본값 없음 |
| `SERVER_PORT` | 기본 `8481` |
| `ADMIN_ALLOWED_ORIGINS` | 쉼표로 구분한 관리자 프론트엔드 Origin. 비어 있으면 동일 Origin만 허용 |
| `ADMIN_ACCESS_TOKEN_MINUTES` | 관리자 토큰 만료 시간. 기본 `30`, 허용 `1~60`분 |

## 인증 및 응답

로그인 외 모든 엔드포인트에 `Authorization: Bearer <관리자 Access Token>`을 보낸다. 일반 서비스 토큰은 관리자 API에서 사용할 수 없으며 관리자 토큰도 일반 API에서 사용할 수 없다. 관리자 토큰은 발급 대상과 용도, 만료, 현재 회원 상태·권한·인증 버전을 검사한다. 관리자용 Refresh Token은 없고 만료 후 다시 로그인한다.

토큰 처리는 `AdminAuthBusiness → AdminTokenBusiness → AdminTokenService → AdminTokenHelperIfs` 순서로 진행한다. Helper는 JWT 서명·클레임 검증을 담당하고, Service는 현재 회원의 활성 상태·관리자 권한·인증 버전을 확인한다. 발급과 폐기는 사용자 잠금을 먼저 획득하며 로그인·로그아웃의 감사 기록과 같은 트랜잭션에 참여한다. 세션 폐기 시 토큰 Repository에서 해당 회원의 모든 활성 Refresh Token을 만료시킨다. 토큰 내부 DTO와 응답 모델은 Converter로 분리하며 로그인 응답의 기존 필드는 유지한다.

JSON 필드명은 `snake_case`, 쿼리 파라미터는 표에 나온 `camelCase`를 사용한다. 쓰기 요청의 Content-Type은 `application/json`이다. 알 수 없는 JSON 필드는 거부한다.

```json
{
  "result": { "result_code": 200, "result_message": "성공", "result_description": "성공" },
  "body": { "items": [], "page": 0, "size": 20, "total_elements": 0, "total_pages": 0 }
}
```

모든 목록은 `page=0`, `size=20`이 기본이며 `size`는 `1~100`, `page`는 `0~100000`이다. 회원·워크스페이스 검색어 `q`는 최대 200자이고 `%`, `_`, `!`도 일반 문자로 검색한다. 회원·워크스페이스·연결·감사 이력은 ID 역순, 멤버와 초대 목록은 회원 ID 순이다. 날짜는 로컬 날짜/시간 문자열이며 운영 기록은 서울 시간을 사용한다. 로그인 응답의 토큰 `expires_at`만 UTC Instant다. 응답은 `Cache-Control: no-store`다.

오류도 같은 봉투 구조이며 `body`는 `null`이다. HTTP `400`은 잘못된 입력, `401`은 인증 실패, `403`은 권한 없음, `404`는 대상 없음, `409`는 상태 또는 동시 변경 충돌이다. 비밀번호, 연락처, 주소, 토큰 해시, 연결 자격 증명은 조회 응답에 포함하지 않는다.

## 인증 API

| 메서드 | 경로 | 입력 / 결과 |
| --- | --- | --- |
| POST | `/auth/login` | `{ "email": "operator@example.com", "password": "..." }` → `access_token`, `token_type`, `expires_at`, `expires_in`(초), `user` |
| GET | `/auth/me` | 현재 관리자 ID·이메일·이름·권한·상태 |
| POST | `/auth/logout` | 해당 회원의 관리자 및 일반 서비스 세션을 모두 종료 |

로그아웃은 현재 토큰만 제거하는 동작이 아니다. 인증 버전을 증가시키고 모든 활성 Refresh Token을 폐기한다. 프론트엔드는 성공 시 보관하던 토큰을 삭제한다.

## 회원 관리

| 메서드 | 경로 | 입력 / 결과 |
| --- | --- | --- |
| GET | `/users` | `q`, `status`, `role`, `page`, `size`로 전체 회원 검색 |
| GET | `/users/{userId}` | 계정 정보, 로그인·가입·수정·탈퇴 시각, 소유/참여 워크스페이스 수 |
| GET | `/users/{userId}/workspaces` | 소유 또는 참여한 워크스페이스 목록, `page`, `size` |
| PATCH | `/users/{userId}/status` | `{ "status": "SUSPENDED", "reason": "운영 정책 위반 확인" }` |
| PATCH | `/users/{userId}/role` | `{ "role": "ADMIN", "reason": "운영 담당자 지정" }` |
| POST | `/users/{userId}/revoke-sessions` | `{ "reason": "계정 소유자 요청" }` |

상태 필터는 `REGISTERED`, `SUSPENDED`, `UNREGISTERED`, 권한은 `ADMIN`, `CUSTOMER`다. 상태 변경은 `REGISTERED`(정지 해제), `SUSPENDED`(이용 정지)만 허용한다. 탈퇴한 `UNREGISTERED` 계정을 이 API로 복구하거나 권한 변경할 수 없다.

자신을 정지하거나 자신의 관리자 권한을 해제할 수 없다. 수정 직전에 작업자와 대상 회원을 일정한 순서로 잠그고 작업자의 현재 권한을 다시 확인한다. 동시 요청으로 서로의 관리자 권한을 해제하더라도 활성 관리자가 모두 사라지지 않는다.

상태·권한이 실제 변경되면 인증 버전을 증가시키고 모든 활성 Refresh Token을 폐기한다. 변경 전에 발급된 Access Token도 이후 요청에서 거부된다. 정지 해제 후에도 새로 로그인해야 한다. 기존 상태와 같은 변경은 `changed=false`이며 토큰이나 감사 이력을 변경하지 않는다. 명시적인 `revoke-sessions`는 활성 Refresh Token이 없어도 인증 버전을 증가시킨다.

```json
{
  "id": 12,
  "status": "SUSPENDED",
  "role": "CUSTOMER",
  "changed": true,
  "revoked_sessions": 2
}
```

위는 `body` 예시다. `revoked_sessions`는 폐기한 활성 Refresh Token 행 수다. 버전이 없는 이전 Access Token은 버전 0으로 취급하여 점진적으로 교체하되, 관리자가 세션을 종료한 계정에는 더 이상 허용하지 않는다. 이미 인증을 마치고 진행 중인 요청을 강제 취소하지는 않는다.

## 운영 현황과 워크스페이스

| 메서드 | 경로 | 입력 / 결과 |
| --- | --- | --- |
| GET | `/overview` | 전체/활성/정지/탈퇴 회원, 활성 관리자, 최근 7일 가입자, 워크스페이스·연결·자산 수, 재인증 필요 연결, 미만료 초대 수 |
| GET | `/workspaces` | `q`(이름·소유자 이메일), `ownerId`, `page`, `size` |
| GET | `/workspaces/{id}` | 이름, 소유자 및 상태, 멤버·연결 수, 생성·수정 시각 |
| GET | `/workspaces/{id}/members` | 멤버 목록과 소유자 여부, `page`, `size` |
| GET | `/workspaces/{id}/invitations` | 초대 대상·만료 시각·만료 여부, `page`, `size` |
| PATCH | `/workspaces/{id}` | `{ "name": "새 워크스페이스 이름", "reason": "요청에 따른 정정" }` |
| PATCH | `/workspaces/{id}/owner` | `{ "expected_owner_id": 12, "new_owner_id": 34, "reason": "담당자 변경" }` |
| POST | `/workspaces/{id}/members/{userId}/remove` | `{ "reason": "퇴사자 접근 해제" }` |
| POST | `/workspaces/{id}/invitations/{userId}/revoke` | `{ "reason": "초대 대상 정정" }` |

소유권은 이미 해당 워크스페이스에 참여 중인 활성 회원에게만 이전한다. 화면에서 조회했던 `expected_owner_id`가 현재 소유자와 다르면 `409`로 거부한다. 이전 소유자는 일반 멤버로 남는다. 소유자를 멤버에서 제거하려면 먼저 소유권을 이전한다. 과거 데이터에 소유자의 멤버 행이 없어도 소유자를 멤버 수와 목록에 포함한다. 초대 토큰이나 해시는 노출하지 않는다.

## 플랫폼 연결과 감사 이력

| 메서드 | 경로 | 입력 / 결과 |
| --- | --- | --- |
| GET | `/connections` | `workspaceId`, `provider`(`META`, `NAVER`, `THREADS`, `GOOGLE`, `COUPANG`), `requiresReauth`, `page`, `size` |
| GET | `/connections/{id}` | 워크스페이스·플랫폼·외부 계정 식별자·계정명·자산 수·연결 토큰 만료 시각·재인증 여부 |
| POST | `/connections/{id}/require-reauth` | `{ "reason": "연결 권한 재확인 필요" }` |
| GET | `/audit-logs` | `actorId`, `action`, `targetType`, `targetId`, `page`, `size` |

재인증 처리는 저장된 연결에 `requires_reauth=true`를 설정한다. Meta/Naver 원격 연결을 해제하거나 광고·상품을 수정하지 않는다. 고객이 일반 서비스의 재연결 흐름을 완료하면 다시 이용할 수 있다. 연결 메타데이터만 조회하며 암호화된 Access/Refresh Token은 반환하지 않는다. 만료 시각은 현재 구현된 Meta/Naver 연결에서 조회하며 만료 정보가 없으면 `null`이다.

`reason`은 선택 입력이다. 생략·null·공백이면 서버가 수행한 동작에 맞는 기본 문구를 기록하며, 메모를 입력하면 앞뒤 공백을 제거해 보존한다. 입력 길이는 최대 500자다. 세션 종료처럼 다른 입력이 없는 작업은 `{}`로 요청할 수 있다. 변경과 감사 기록은 같은 트랜잭션이므로 감사 기록 저장 실패 시 변경도 롤백된다. 로그인·로그아웃도 정해진 사유로 기록한다. 변경 없는 요청은 추가 기록하지 않는다. 감사 응답은 작업자 ID, 대상 종류·ID, 동작, 사유, 변경 전·후 값, 시각이다. 사유에 비밀번호나 토큰 등 비밀정보를 입력하지 않는다.

| `action` | `targetType` |
| --- | --- |
| `ADMIN_LOGIN`, `ADMIN_LOGOUT` | `USER` |
| `USER_STATUS_CHANGED`, `USER_ROLE_CHANGED`, `USER_SESSIONS_REVOKED` | `USER` |
| `WORKSPACE_RENAME`, `WORKSPACE_TRANSFER_OWNER`, `WORKSPACE_REMOVE_MEMBER`, `WORKSPACE_REVOKE_INVITATION` | `WORKSPACE` |
| `CONNECTION_REQUIRE_REAUTH` | `CONNECTION` |

워크스페이스·연결 변경 성공 `body`는 `{ "id": 1, "changed": true }`다. 삭제·비밀번호 대리 변경 API는 제공하지 않는다. API 문서 경로 `/v3/api-docs`도 관리자 인증이 필요하며 Swagger UI는 기본 비활성화다.

## 검증

```sh
./gradlew :admin-api:test :api:test
./gradlew :admin-api:bootJar
```

관리자 인증·접근 제어, H2 기반 목록/권한 변경/트랜잭션 롤백, 동시 관리자 권한 변경, 일반 API의 토큰 검증·갱신과 관리자 세션 폐기 간 경쟁, 전체 관리자 애플리케이션의 HTTP 흐름을 자동 테스트한다. 테스트는 실제 운영 DB, 외부 플랫폼 계정, 광고·상품을 변경하지 않는다.
