# 워크스페이스 이메일 초대

소유자가 등록된 사용자들의 `user_ids`로 초대하면 각 계정의 이메일로 개별 참여 링크를 보냅니다. 초대받은 계정이 로그인하고 수락해야 `workspace_members`에 등록됩니다. 워크스페이스 생성자의 자동 등록과 기존 추방 API는 유지됩니다.

## 설정

운영 `application-prod.yml`에서 수락 페이지 주소를 환경변수로 설정합니다. 로컬은 `application-local.yml`의 `app.workspace-invitation.accept-url`을 사용합니다.

```dotenv
WORKSPACE_INVITATION_ACCEPT_URL=https://your-frontend.example/workspace-invitations/accept
```

`WORKSPACE_INVITATION_ACCEPT_URL`은 프런트엔드의 초대 수락 페이지 전체 주소입니다. 메일에는 이 주소에 `token` 쿼리 파라미터를 붙인 링크가 들어갑니다. 주소가 비어 있으면 초대 요청은 오류로 종료됩니다. 발신자는 기존 SMTP 계정인 `spring.mail.username`을 사용하므로 별도 `from` 설정은 필요하지 않습니다. UUID는 초대를 식별하는 값이므로 수락 페이지의 기본 주소는 계속 필요합니다.

로컬은 기존 `spring.mail` 설정을 사용합니다. 운영 SMTP 설정은 다음 환경변수로 지정합니다.

```dotenv
MAIL_HOST=smtp.example.com
MAIL_PORT=587
MAIL_USERNAME=no-reply@example.com
MAIL_PASSWORD=your-smtp-password
MAIL_SMTP_AUTH=true
MAIL_STARTTLS_ENABLE=true
```

운영의 `ddl-auto`는 `validate`이므로 배포 전에 `db/migrations/20260919_workspace_invitations.sql`을 DB에 적용해야 합니다. 이 SQL은 자동 실행되지 않습니다. 로컬도 테이블이 없다면 적용해야 합니다. `application-local.yml`은 기존 `.gitignore` 대상이므로 저장소에는 포함되지 않습니다.

## API와 프런트엔드 연결

두 API 모두 로그인된 사용자의 `Authorization: Bearer <accessToken>`이 필요합니다.

### 초대

`POST /api/workspace-members/invite`

```json
{"workspace_id": 1, "user_ids": [2, 3, 4]}
```

소유자만 호출할 수 있습니다. 기존 `user_id` 대신 `user_ids` 배열을 보내야 합니다. 한 번에 1~50개 ID를 받으며, 중복 ID는 첫 등장 순서대로 한 번만 처리합니다. 한 명만 초대할 때도 `[2]`처럼 배열로 보냅니다.

요청 안에서 사용자별로 순차 발송하고 결과를 `Api<List<WorkspaceMemberInviteResponse>>`의 `body`에 반환합니다. 각 발송은 독립된 트랜잭션이므로 일부 사용자 조회·메일 전송·DB 저장이 실패해도 나머지는 계속 처리하고 성공한 초대를 유지합니다. 이미 멤버인 사용자도 개별 실패 결과로 반환합니다.

```json
[
  {"user_id": 2, "success": true, "message": "초대 메일을 발송했습니다"},
  {"user_id": 3, "success": false, "message": "초대 메일 발송에 실패했습니다"},
  {"user_id": 4, "success": false, "message": "이미 등록된 워크스페이스 멤버입니다"}
]
```

배치 응답의 HTTP 200은 모든 발송의 성공을 뜻하지 않습니다. 프런트엔드는 각 항목의 `success`를 확인하고 실패한 사용자만 재시도해야 합니다. 워크스페이스가 없거나 요청자가 소유자가 아니거나 입력값 검증이 실패하면 메일을 보내지 않고 요청 전체를 거절합니다.

### 참여 수락

새 초대 링크는 `http://localhost:3000/invite?token=550e8400-e29b-41d4-a716-446655440000`처럼 랜덤 UUID(v4)를 포함합니다. 프런트엔드 수락 페이지는 링크의 `token`을 보관하고, 필요하면 로그인으로 안내한 뒤 참여 버튼에서 다음 API를 호출해야 합니다. 페이지를 열기만 해서는 멤버가 추가되지 않습니다. 이 저장소에는 프런트엔드 수락 화면이 포함되어 있지 않습니다.

`POST /api/workspace-members/accept`

```json
{"token": "메일 링크의 token 값"}
```

성공 시 `Api<WorkspaceMemberResponse>`를 반환합니다. 초대받은 사용자 ID와 로그인 사용자 ID가 같아야 합니다.

다른 계정이 수락하면 HTTP 403과 "초대받은 사용자만 수락할 수 있습니다. 초대 메일을 받은 계정으로 로그인해 주세요" 메시지를 반환합니다. 워크스페이스 소유자도 다른 사람의 초대를 대신 수락할 수 없습니다. 이때 멤버는 추가되지 않고 초대도 소모되지 않으므로, 초대받은 계정으로 다시 로그인하여 같은 링크를 사용할 수 있습니다. 사용자 ID는 요청 본문이 아닌 인증된 세션에서 가져옵니다.

- 링크 유효기간은 발급 후 24시간입니다.
- 동일 사용자에게 재초대하면 이전 링크는 무효화됩니다.
- 수락 시 멤버 저장과 초대 삭제를 하나의 트랜잭션으로 처리합니다. 사용한 링크로 재수락하거나 추방 후 재가입할 수 없습니다.
- DB에는 UUID 토큰의 SHA-256 해시만 저장합니다. 이미 발송된 기존 형식의 토큰도 만료 전까지 사용할 수 있습니다.
- SMTP 전송 실패 시 초대 저장을 롤백합니다. 재발송 실패 시 기존 링크는 유지됩니다.
- SMTP와 DB는 분산 트랜잭션이 아니므로 메일 발송 후 DB 커밋이 실패한 경우에는 재초대가 필요합니다.

## 검증

```sh
./gradlew :api:test --tests com.orinan.api.domain.workspacemember.WorkspaceInvitationFlowTest --offline
```

H2 DB와 모의 메일 발송기로 초대, 수락, 만료, 권한, 재전송, 토큰 재사용, 트랜잭션 롤백, 배치 부분 실패, 중복 제거 및 목록 입력값 검증을 수행합니다. 실제 이메일은 발송하지 않습니다.
