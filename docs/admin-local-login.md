# 로컬 관리자 로그인

관리자 API는 `http://localhost:8481`, 관리자 화면은 `http://localhost:3400/admin/login`이다. 화면에서 입력한 이메일·비밀번호로 공유 서비스 DB의 회원을 인증한다. 기본 관리자 아이디·비밀번호와 공개 관리자 가입 기능은 없다.

## 1. 주소와 연결 설정

| 구분 | 주소 | 역할 |
| --- | --- | --- |
| 프론트엔드 | `http://localhost:3400` | 일반 서비스와 관리자 화면 |
| 관리자 로그인 | `http://localhost:3400/admin/login` | 운영자 로그인 폼 |
| 일반 API | `http://localhost:8480` | 서비스 가입·로그인·고객 화면 |
| 관리자 API | `http://localhost:8481/admin-api` | 관리자 인증·운영·기술 지원 |

`ad-frontend/vite.config.ts`는 `/admin-api` 요청을 `http://localhost:8481`로 전달한다. `/api`와 `/open-api`는 `8480`으로 전달한다. 로컬에서는 `ad-frontend/.env.local`의 `VITE_ADMIN_API_BASE_URL`을 설정하지 않거나 빈 문자열로 둔다.

```dotenv
VITE_ADMIN_API_BASE_URL=
```

Vite 프록시를 쓰면 별도의 관리자 CORS 설정 없이 같은 프론트엔드 주소로 요청한다. 기존 환경 변수에 다른 주소가 설정되어 있으면 프록시를 우회하므로 제거한 뒤 개발 서버를 다시 시작한다. 값을 직접 설정할 때는 `/admin-api`를 붙이지 않은 서버 주소를 사용한다. 예를 들어 `VITE_ADMIN_API_BASE_URL=http://localhost:8481`이라면 관리자 서버에도 `ADMIN_ALLOWED_ORIGINS=http://localhost:3400`을 설정하고 양쪽을 재시작해야 한다.

## 2. DB와 서버 준비

일반 API와 관리자 API는 같은 DB를 사용해야 한다. 각 모듈의 `src/main/resources/application-local.yml`에 있는 DB 접속 설정과 개인정보 암호화 키를 일치시킨다. 관리자 서버에도 `token.secret.key`가 필요하다. 키와 비밀번호를 프론트엔드 환경 변수에 넣지 않는다.

현재 코드를 처음 실행하는 DB라면 다음 SQL을 순서대로 한 번 적용한다. 이미 적용한 파일은 다시 실행하지 않는다.

1. [20260928_admin_operations.sql](../db/migrations/20260928_admin_operations.sql): `users.auth_version`, 회원 정지 상태, `admin_audit_logs`.
2. [20260928_support_sessions.sql](../db/migrations/20260928_support_sessions.sql): 지원 요청·세션·작업 이력 테이블.

자동 마이그레이션은 없으며 공유 Entity가 두 서버의 스키마 검증에 포함된다. 이번 안내 작성 과정에서는 실제 DB에 SQL을 실행하거나 회원 권한을 변경하지 않았다.

프로젝트 루트(`ad`)에서 아래 명령을 **각각 다른 터미널**에서 실행한다. 이미 해당 서버가 실행 중이면 같은 포트에 중복 실행하지 않는다.

```sh
cd ad-backend
./gradlew :api:bootRun --args='--spring.profiles.active=local --server.port=8480'
```

```sh
cd ad-backend
./gradlew :admin-api:bootRun --args='--spring.profiles.active=local --server.port=8481'
```

```sh
cd ad-frontend
npm run dev
```

관리자 기본 포트는 `8481`이다. `SERVER_PORT`, IDE 실행 설정, 외부 설정 파일이 포트를 덮어쓸 수 있으므로 실행 로그의 포트를 확인한다. 위 명령의 `--server.port`는 해당 실행의 포트를 명시한다.

## 3. 관리자 계정 준비

이미 가입한 본인의 개발자 계정을 사용하면 된다. 계정이 없으면 `http://localhost:3400/signup`에서 먼저 가입한다. 이메일·비밀번호·이름·휴대폰 번호가 필수이며 가입 시 역할은 `CUSTOMER`, 상태는 `REGISTERED`로 저장된다. 비밀번호는 BCrypt로 해시되므로 DB에 평문 비밀번호를 직접 넣지 않는다.

관리자 로그인 조건은 다음 세 가지다.

- `users.role = 'ADMIN'`
- `users.status = 'REGISTERED'`
- 해당 회원이 서비스 가입 때 설정한 비밀번호와 일치

이미 다른 관리자 계정이 있다면 그 계정으로 `/admin/users`에서 회원 상세 → **권한 변경** → **관리자**를 선택할 수 있다. 최초 관리자가 없다면 DB에 접근할 수 있는 운영자가 아래 절차로 본인 계정 하나만 지정한다.

먼저 실제 이메일로 회원 ID와 현재 상태를 확인한다. `operator@example.com`은 설명용 예시다.

```sql
SELECT id, email, status, role
FROM users
WHERE LOWER(email) = LOWER('operator@example.com');
```

결과가 이미 `ADMIN` / `REGISTERED`이면 승격 SQL은 생략하고 로그인한다. `CUSTOMER` / `REGISTERED` 회원을 최초 관리자로 지정할 때는 아래의 `123`과 이메일을 **위에서 확인한 실제 값으로 모두 교체**한다.

```sql
START TRANSACTION;

SELECT id, email, status, role
FROM users
WHERE id = 123
FOR UPDATE;

UPDATE users
SET role = 'ADMIN',
    auth_version = auth_version + 1,
    updated_at = CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')
WHERE id = 123
  AND email = 'operator@example.com'
  AND status = 'REGISTERED'
  AND role = 'CUSTOMER';

SELECT ROW_COUNT() AS changed_users;
```

**`changed_users = 1`인 경우에만**, 같은 DB 세션에서 다음을 실행해 기존 Refresh Token도 폐기하고 커밋한다.

```sql
UPDATE tokens
SET status = 'EXPIRED',
    revoked_at = CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00')
WHERE user_id = 123 AND status = 'ACTIVE';

COMMIT;
```

`changed_users`가 1이 아니면 같은 세션에서 `ROLLBACK;`을 실행하고 ID·이메일·상태·역할을 다시 확인한다. 최초 DB 지정 작업은 별도 운영 기록에 남긴다. 이후 관리자 UI의 권한 변경은 서비스의 감사 이력에 기록된다.

비밀번호는 변경하지 않는다. 권한 변경으로 이전 세션이 무효화되므로 관리자 화면에서 새로 로그인한다.

## 4. 로그인

1. `http://localhost:3400/admin/login`을 연다.
2. 위에서 `ADMIN`으로 지정한 회원의 이메일을 입력한다.
3. 해당 회원의 기존 서비스 비밀번호를 입력한다.
4. **운영 콘솔 로그인**을 누르면 `/admin`의 운영 대시보드로 이동한다.

브라우저 요청은 다음과 같이 전달된다.

```text
로그인 화면 :3400/admin/login
  → POST :3400/admin-api/auth/login
  → Vite 프록시
  → POST :8481/admin-api/auth/login
```

프론트엔드는 로그인 성공 후 관리자 전용 Access Token을 별도 `sessionStorage`에 저장하고 `/admin-api/auth/me`로 현재 권한을 확인한다. 일반 서비스 로그인 토큰으로 관리자 API를 호출할 수 없다. 관리자 토큰은 기본 30분 뒤 만료되며 자동 갱신 없이 다시 로그인한다. 관리자 로그아웃은 해당 회원의 관리자·일반 서비스 세션을 함께 무효화한다.

API 클라이언트에서 직접 확인할 때는 다음 요청을 보낸다. 예시 문자열을 실제 계정으로 교체하며 토큰과 비밀번호는 외부에 공유하지 않는다.

```http
POST http://localhost:8481/admin-api/auth/login
Content-Type: application/json

{
  "email": "operator@example.com",
  "password": "가입할 때 설정한 비밀번호"
}
```

성공 응답의 `body.access_token`을 사용해 `GET http://localhost:8481/admin-api/auth/me`에 `Authorization: Bearer <access_token>`을 보내면 관리자 계정을 확인할 수 있다.

## 5. 로그인 문제 확인

| 증상 | 확인할 내용 |
| --- | --- |
| `8481`을 열었는데 로그인 화면이 안 보임 | `8481`은 API 서버다. 화면은 `3400/admin/login`에서 연다. |
| 이메일·비밀번호 확인 메시지 / HTTP 401 | 비밀번호 외에도 `role=ADMIN`, `status=REGISTERED`인지 확인한다. 관리자 권한이 없는 경우도 같은 로그인 오류를 반환한다. |
| 권한 변경 직후 기존 화면에서 401 | 이전 세션은 무효화된다. 관리자 로그인 화면에서 새 토큰을 발급받는다. |
| 서버 연결 실패 / 프록시 오류 | 관리자 서버가 `8481`에 떠 있는지, `VITE_ADMIN_API_BASE_URL`에 예전 주소가 남아 있지 않은지 확인한다. 설정 변경 후 프론트엔드를 재시작한다. |
| CORS 오류 | 기본 로컬 설정은 빈 `VITE_ADMIN_API_BASE_URL`과 Vite 프록시다. 직접 호출한다면 관리자 서버의 허용 Origin을 정확히 설정한다. |
| 서버 시작 시 `auth_version` / 테이블 누락 | 두 SQL 마이그레이션 적용 여부와 두 서버가 같은 DB를 사용하는지 확인한다. |
| 로그인 시 서버 오류 / HTTP 500 | 관리자 로그인도 감사 이력을 기록한다. `admin_audit_logs`, 토큰 서명 설정, DB·암호화 설정과 서버 로그를 확인한다. |

기술 지원 기능 사용 방법은 [지원 운영 문서](support-operations.md)를 참고한다.
