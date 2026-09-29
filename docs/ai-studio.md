# AI 스튜디오

운영자는 카테고리와 소재 유형을 선택하고 샘플 이미지를 업로드합니다. OpenAI가 이미지의 스타일을 분석해 이름·설명·생성 지침을 자동으로 채우며 필요할 때만 수정합니다. 고객은 상품 정보와 선택적 실제 상품 사진, 추가 요청으로 광고 이미지 또는 판매용 상세페이지를 생성합니다. 결과는 워크스페이스에 저장되며 광고 이미지는 Meta 광고 등록에, 상세페이지는 네이버 스마트스토어·아임웹 상품 등록에 사용합니다.

## 실행 전 설정

일반 API(`8480`)와 관리자 API(`8481`)는 같은 서비스 DB와 S3 버킷을 사용합니다. 프론트엔드는 기존 `3400` 포트입니다.

1. 서비스 DB에 다음 SQL을 순서대로 적용합니다. 애플리케이션이 자동 실행하지 않습니다.
   - [`20260928_ai_studio_templates.sql`](../db/migrations/20260928_ai_studio_templates.sql): 샘플과 업로드 이미지 등록부
   - [`20260928_ai_studio_outputs.sql`](../db/migrations/20260928_ai_studio_outputs.sql): 고객 생성 결과와 요청 중복 방지 키
   - [`20260928_ai_studio_categories.sql`](../db/migrations/20260928_ai_studio_categories.sql): 카테고리 마스터와 샘플 외래키, 기존 텍스트 카테고리 이관

   앞의 두 SQL을 이미 적용했다면 카테고리 SQL만 적용합니다. 카테고리 전환은 기존 샘플의 문자열을 카테고리로 모으고 `category_id`를 채운 뒤 기존 문자열 컬럼을 제거합니다. 빈 카테고리는 `미분류`로 보존합니다. 전환 중에는 두 서버의 AI 스튜디오 쓰기를 중지하고, SQL 적용 후 새 백엔드·프론트엔드를 함께 배포합니다.
2. 기존 `app.ai-creative.api-key`를 고객 생성과 관리자 샘플 분석에서 함께 사용합니다. 각 서버의 `application-local.yml`에 이 키가 있으면 추가 키 설정은 필요하지 않습니다. 환경변수로 설정할 때는 `OPEN_API_KEY`를 사용합니다. 이전 이름인 `OPENAI_API_KEY`도 호환됩니다. 로컬 YAML에 설정하려면 기존 `app` 항목 아래 다음 값을 합칩니다. `app` 키를 중복해서 만들지 않습니다.

   ```yaml
   app:
     ai-creative:
       api-key: ${OPEN_API_KEY:${OPENAI_API_KEY:}}
     ai-studio:
       openai:
         image-model: ${OPENAI_IMAGE_MODEL:gpt-image-2.5-sunburst}
         text-model: ${OPENAI_TEXT_MODEL:gpt-4o-mini}
   ```

   고객 결과 생성은 일반 API 설정을, 샘플 자동 분석은 관리자 API 설정을 사용합니다. 두 서버 모두 키는 `app.ai-creative.api-key` → `OPEN_API_KEY` → `OPENAI_API_KEY` 순서로 읽습니다. `app.ai-studio.openai.api-key`를 별도로 명시하면 그 값이 우선합니다. 관리자 API도 같은 키 경로와 선택적 `app.ai-studio.openai.text-model`을 사용합니다. 서버를 각각 실행한다면 각 프로세스에 환경 변수를 전달합니다. 프론트엔드의 `VITE_` 환경 변수에는 비밀 키를 넣지 않습니다. 일반 API 키가 없으면 고객 생성 버튼은 준비 중으로 표시됩니다. 관리자 API 키가 없거나 분석이 실패해도 샘플 이미지는 저장할 수 있습니다. 사용하는 OpenAI 프로젝트에서 해당 모델을 사용할 수 있어야 합니다.
3. 두 서버의 S3 설정을 확인합니다. 기존 일반 API의 S3 설정을 그대로 사용할 수 있고, 관리자 API에도 같은 버킷과 이미지 경로에 대한 읽기·쓰기 권한이 필요합니다.

   ```yaml
   app:
     aws:
       s3:
         bucket: ${S3_BUCKET_NAME}
         key-prefix: ${S3_KEY_PREFIX:auto-threads}
   cloud:
     aws:
       region:
         static: ${AWS_REGION:ap-northeast-2}
       credentials:
         access-key: ${AWS_IAM_ACCESS_KEY}
         secret-key: ${AWS_IAM_SECRET_KEY}
   ```

   로컬에서는 각 모듈의 `application-local.yml`에 설정합니다. 운영 환경의 기본 prefix는 `ad-backend`입니다. 실제 일반 API 설정과 관리자 설정을 일치시키세요. 버킷을 공개할 필요는 없으며 미리보기에는 1시간 서명 URL을 사용합니다.
4. 일반 API와 관리자 API를 재시작합니다. `ad-frontend`에서 `npm run dev`로 프론트엔드를 실행합니다.

SQL 적용, 실제 OpenAI 호출, S3 업로드, 광고·상품 등록은 개발 검증 중 실행하지 않았습니다. 실제 생성은 OpenAI 프로젝트에 과금될 수 있습니다.

## 화면 사용 순서

1. `http://localhost:3400/admin/studio`에서 관리자 권한으로 로그인합니다. **카테고리 관리**(`/admin/studio/categories`)에서 뷰티·식품·라이프스타일 등 사용할 카테고리를 먼저 등록합니다. [관리자 로그인 안내](admin-local-login.md)를 참고할 수 있습니다.
2. **샘플 등록**에서 광고 이미지 또는 상품 상세페이지 유형과 카테고리를 선택하고 사진을 올립니다. 샘플 입력 중에도 카테고리를 추가할 수 있습니다. 업로드가 완료되면 이미지가 OpenAI로 전달되어 구도·색감·조명·여백·텍스트 배치를 분석합니다. 이름·설명·생성 지침은 자동 입력되며 **AI 분석 내용 수정**을 펼쳐 필요할 때만 수정합니다. 분석 실패 시 재업로드 없이 기본 내용으로 저장하거나 분석을 다시 요청할 수 있습니다. **고객에게 공개**를 체크해 저장하면 고객이 선택할 수 있습니다. 기존 이미지 없는 수동 초안 편집은 유지하지만 공개에는 업로드된 이미지가 필요합니다.
3. 고객은 `/workspaces/{workspaceId}/studio` 또는 워크스페이스 사이드바의 **AI 스튜디오**에서 공개된 샘플을 선택합니다. 상품명·설명을 입력하고 실제 상품 사진, 주요 고객, 추가 요청을 선택적으로 넣습니다.
4. **생성**을 누르면 결과가 저장됩니다. 광고 이미지는 정사각형 이미지이고, 상세페이지는 세로 이미지와 상품 소개·특징·설명으로 구성됩니다. 상세페이지는 서버가 안전한 HTML 구조로 조립합니다.
5. 광고 이미지 결과에서는 연결된 Meta 광고 계정을, 상세페이지 결과에서는 연결된 스마트스토어·아임웹 스토어를 선택하고 등록 화면으로 이동합니다. 결과 유형에 맞는 채널만 표시·조회합니다. **등록 화면에 적용**을 누르면 광고 이미지는 Meta 소재로, 상세페이지는 선택한 쇼핑몰의 상품 이미지와 상세 내용으로 적용됩니다. 반대 유형의 결과 ID를 URL로 직접 전달해도 적용할 수 없습니다. 나머지 필수 정보를 입력하고 기존 검토·등록 절차를 진행합니다. AI 생성이나 적용만으로 광고·상품이 등록되지는 않습니다.

Meta에서는 `AD_IMAGE` 결과만 가져오며 기존 광고 계정별 이미지 업로드 API로 옮깁니다. 계정 변경 시 기존 업로드의 귀속을 유지하지 않습니다.

스마트스토어에서는 `DETAIL_PAGE` 결과만 가져옵니다. 상세페이지 이미지를 상품 이미지로 사용하고 결과 ID를 `studio_output_id`로 함께 전달합니다. 서버가 워크스페이스 권한과 완료 상태, 상세페이지 유형을 확인한 뒤 저장된 상세 HTML을 불러오고, 이미지를 네이버에 업로드한 영구 주소로 교체합니다. 브라우저에서 받은 임의 HTML이나 만료되는 S3 주소를 상품 상세 내용으로 저장하지 않습니다. 추가 설명은 일반 텍스트로 이스케이프하며, AI 상세페이지를 연결한 경우 생략할 수 있습니다. **AI 상세페이지 연결 해제**로 기존 일반 텍스트 방식으로 돌아갈 수 있습니다.

아임웹에서도 `DETAIL_PAGE` 결과를 사용할 수 있습니다. 서버가 저장된 상세페이지 이미지를 판매 중지 상품에 먼저 업로드하고, 아임웹의 영구 이미지 주소로 상세 HTML을 적용한 뒤 판매 상태로 변경합니다. 중간에 실패하면 생성된 상품 ID를 보존하여 중복 등록을 방지합니다. 연결 설정과 지원 범위는 [아임웹 연동 문서](imweb-integration.md)를 참고하세요.

현재 기술 지원 세션으로는 AI 스튜디오를 사용할 수 없습니다. 고객 계정으로 직접 로그인해야 합니다.

카테고리 이름 변경은 연결된 모든 샘플에 반영됩니다. 같은 이름의 중복 등록은 거부하며, 공개·비공개 샘플에서 사용 중인 카테고리는 삭제할 수 없습니다. 먼저 해당 샘플을 다른 카테고리로 옮겨야 합니다. 고객 카테고리 필터에는 공개된 샘플이 있는 항목만 표시합니다.

## API

서비스 규칙대로 JSON은 snake_case, 응답 데이터는 `Api.body`를 사용합니다. 관리자와 고객은 각각 기존 인증 토큰을 사용합니다.

### 관리자

공통 경로: `/admin-api/ai-studio/templates`

| 메서드·경로 | 기능 |
| --- | --- |
| `GET /` | 전체 샘플 목록. `page`, `size`, `kind`, `categoryId`, `q` 지원 |
| `GET /{id}` | 생성 지침과 공개 상태를 포함한 상세 |
| `POST /` | 샘플 등록 |
| `PATCH /{id}` | 샘플 편집·공개 또는 비공개 |
| `POST /images` | multipart `file` 이미지 업로드 |
| `POST /analyze` | 등록된 `image_key`, `kind`로 샘플 이미지의 스타일 분석 |

샘플 필드는 `kind`(`AD_IMAGE`, `DETAIL_PAGE`), `title`, `description`, `category_id`, `prompt`, `preview_image_key`, `published`입니다. 신규 등록은 존재하는 카테고리 ID가 필수이며 자유 입력 `category` 필드는 받지 않습니다. 등록된 이미지가 있으면 `title`, `description`, `prompt`는 생략할 수 있고, 비어 있는 항목에는 카테고리·유형 기반 이름과 이미지 참고용 기본 설명·지침을 저장합니다. 분석이 성공했다면 반환된 세 항목을 저장 요청에 포함합니다. 응답에는 `category_id`, `category_name`이 포함됩니다. 이미지 업로드 응답의 `image_key`를 `preview_image_key`로 저장합니다. 외부 이미지 URL을 직접 저장하지 않습니다. 수정 시 생략·null 필드는 유지하고, 이미지가 있는 샘플의 이름·지침을 빈 문자열로 보내면 기본값을 사용합니다. `preview_image_key: ""`는 초안 이미지를 제거합니다. 현재 관리자 권한을 확인하고 변경 이력을 남깁니다.

분석 요청·응답 예시:

```json
{ "image_key": "업로드 응답의 image_key", "kind": "AD_IMAGE" }
```

```json
{
  "title": "자연광과 여백을 활용한 미니멀 스타일",
  "description": "밝은 배경과 차분한 색감으로 상품을 강조하는 구성입니다.",
  "prompt": "부드러운 자연광, 넓은 여백, 중앙 상품 배치를 참고하고 실제 상품과 문구는 고객 정보를 사용하세요."
}
```

분석 API는 업로드 등록부와 현재 관리자 권한을 확인한 뒤 S3 이미지 바이트를 읽습니다. 외부 URL이나 임의 S3 객체를 입력으로 받지 않습니다. OpenAI에는 이미지와 소재 유형을 전달하고, 구조화된 텍스트 결과를 검증해 반환합니다. 샘플 템플릿은 분석만으로 저장·공개되지 않습니다. 분석 POST는 자동 재시도하지 않으며 실패해도 이미 업로드된 이미지는 유지합니다. 이미지 교체·화면 이탈 후 도착한 이전 응답은 화면에 적용하지 않고, 직접 편집한 항목은 자동 분석으로 덮어쓰지 않습니다.

카테고리 공통 경로: `/admin-api/ai-studio/categories`

| 메서드·경로 | 기능 |
| --- | --- |
| `GET /` | 카테고리 배열 `[{"id": 1, "name": "뷰티"}]` |
| `POST /` | `{"name": "뷰티"}`로 등록. 이름은 공백 제외 1~80자 |
| `PATCH /{id}` | `{"name": "생활용품"}`로 이름 변경 |
| `DELETE /{id}` | 미사용 카테고리 삭제. 사용 중이면 `409` |

`ai_templates.category_id`는 `ai_studio_categories.id`를 참조하는 NOT NULL 외래키이며 DB에서도 참조 중인 카테고리 삭제를 제한합니다. 카테고리 변경은 관리자 감사 이력에 남습니다.

### 고객

공통 경로: `/api/workspaces/{workspaceId}/ai-studio`

| 메서드·경로 | 기능 |
| --- | --- |
| `GET /capabilities` | 생성 준비 상태 |
| `GET /categories` | 공개된 샘플이 있는 카테고리 배열 `[{"id": 1, "name": "뷰티"}]` |
| `GET /templates` | 공개 샘플 목록. `page`, `size`, `kind`, `categoryId`, `q` 지원 |
| `GET /templates/{id}` | 공개 샘플 상세. 운영자 생성 지침은 노출하지 않음 |
| `POST /images` | 선택적 실제 상품 사진 업로드. multipart `file` |
| `POST /generations` | 생성 요청 |
| `GET /outputs` | 워크스페이스 결과 목록. `page`, `size`, `kind` 지원 |
| `GET /outputs/{id}` | 생성 상태·결과 조회 |
| `GET /outputs/{id}/image` | 인증된 완료 이미지 다운로드 |

생성 요청 예시:

```json
{
  "template_id": 1,
  "product_name": "데일리 머그컵",
  "product_description": "흰색 도자기 머그컵, 용량 300ml",
  "audience": "집에서 커피를 즐기는 고객",
  "instructions": "밝은 자연광과 베이지색 배경",
  "idempotency_key": "9b1cfdd5-c576-47b2-86ad-6c798fc76dcb"
}
```

상품 사진을 올렸다면 같은 사용자·워크스페이스의 업로드 응답 `image_key`를 `product_image_key`로 추가합니다. 이미지 입력은 JPEG·PNG, 최대 10MiB입니다. 결과 이미지에도 기존 채널 업로드와 같은 10MiB 제한을 적용합니다.

결과에는 `id`, `workspace_id`, `template_id`, `kind`, `title`, `status`, `image_url`, `detail_html`, `created_at`이 있습니다. 상태는 `PENDING`, `SUCCEEDED`, `FAILED`입니다. 상세 HTML에는 서버가 만든 `{{STUDIO_IMAGE}}` 자리표시자가 한 번 들어가며 미리보기와 채널 등록 시 각각 적절한 URL로 치환합니다.

## 실패·중복 처리

- 생성 전에 DB에 `PENDING`을 커밋합니다. 같은 사용자·워크스페이스의 같은 UUID와 입력은 기존 결과를 반환하며 모델을 다시 호출하지 않습니다. 같은 UUID의 다른 입력은 거부합니다.
- OpenAI 요청은 자동 재시도하지 않습니다. 프론트엔드도 생성 POST를 인증 갱신 후 재전송하지 않습니다. 응답을 확정하지 못하면 **내 결과**에서 먼저 상태를 확인합니다.
- 원격 처리 후 현재 회원·워크스페이스 권한을 다시 검사합니다. 다른 워크스페이스의 결과·상품 사진은 사용할 수 없습니다.
- 서버 재시작 등으로 남은 `PENDING` 요청을 자동 재생성하는 복구 작업은 없습니다. 이런 요청은 OpenAI 처리 여부를 확인한 뒤 운영자가 대응해야 합니다.
- S3에는 원본 객체 키를 저장하며 조회 시 서명 URL을 새로 만듭니다. 화면을 오래 열어 이미지가 만료되면 목록 또는 결과를 새로고침합니다.
- 모델의 HTML·스크립트는 사용하지 않습니다. 상세 문구는 구조화된 JSON을 검증한 뒤 서버에서 이스케이프합니다. 생성 이미지·문구와 실제 상품 사실은 등록 전에 확인합니다.

## 검증

관리자 권한·샘플 공개 규칙·업로드·카테고리 중복/참조/삭제 제약, 고객 카테고리 필터·이름 변경 반영·권한·중복 요청·동시 요청·입력 범위·원격 오류·JSON/이미지 검증, 네이버 영구 이미지 전환·추가 설명 이스케이프·등록 전 권한 회수를 모의 HTTP와 단위·MVC·DB 테스트로 검증합니다. 브라우저 검증은 모의 API로 샘플 등록, 생성 성공·실패·결과 불명확, 중복 클릭, 채널 적용, 모바일 화면을 확인합니다.

OpenAI 연동 형식은 [이미지 입력·분석 가이드](https://developers.openai.com/api/docs/guides/images-vision), [이미지 생성 가이드](https://developers.openai.com/api/docs/guides/image-generation), [이미지 편집 API](https://developers.openai.com/api/reference/resources/images/methods/edit), [구조화된 응답 가이드](https://developers.openai.com/api/docs/guides/structured-outputs)를 기준으로 구현했습니다.
