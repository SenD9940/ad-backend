# 아임웹 연결·상품·판매 성과

아임웹 개발자센터 앱의 Client ID·Secret을 사용하는 최신 OpenAPI OAuth 연결입니다. 기존 REST API의 API Key·Secret Key 연결과는 다릅니다. 워크스페이스 소유자가 사이트를 인증하고, 소유자·멤버가 해당 사이트의 스토어를 선택해 저장합니다. 저장 이후에는 판매 중 상품과 판매 성과 화면이 바로 열립니다.

## 서버 설정

1. 기존 플랫폼 테이블이 있는 서비스 DB에 [`20260929_imweb_platform_integration.sql`](../db/migrations/20260929_imweb_platform_integration.sql)을 적용합니다. 애플리케이션이 자동 실행하지 않습니다. 기존 enum 값을 보존하면서 IMWEB/STORE를 추가하고 토큰·스토어 보조 테이블을 생성합니다. 토큰은 기존 DB 암호화 설정을 사용합니다. Redis도 실행되어 있어야 합니다.
2. 일반 API(`8480`)의 `application-local.yml`에서 기존 `app` 아래에 다음 설정을 합칩니다. `app` 키를 중복하지 않습니다. 환경변수로도 설정할 수 있습니다. Client Secret은 서버에만 저장하며 프론트엔드 `VITE_` 변수에 넣지 않습니다.

   ```yaml
   app:
     imweb:
       enabled: true
       client-id: ${IMWEB_CLIENT_ID}
       client-secret: ${IMWEB_CLIENT_SECRET}
       redirect-uri: http://localhost:3400/open-api/platform-connections/imweb/callback
       frontend-redirect-uri: http://localhost:3400/settings/integrations/imweb/callback
   ```

   YAML을 추가하지 않고 `IMWEB_ENABLED=true`, `IMWEB_CLIENT_ID`, `IMWEB_CLIENT_SECRET` 환경변수만 설정해도 위 로컬 리디렉션 주소를 기본 사용합니다. 운영 주소는 `IMWEB_REDIRECT_URI`, `IMWEB_FRONTEND_REDIRECT_URI`로 변경합니다.
3. 개발자센터 앱의 OAuth 리디렉션 URI를 위 `redirect-uri`와 정확히 맞춥니다. 서비스 URL은 `http://localhost:3400/settings/integrations/imweb/connect`입니다. 앱 사용 신청에서 전달되는 `siteCode` 쿼리를 받아 로그인한 고객의 워크스페이스 목록을 표시합니다. 로컬 URL을 앱 설정에서 허용하지 않는 경우 HTTPS 개발 도메인/터널을 사용하고 두 리디렉션 URI도 같은 도메인으로 맞춥니다. 운영은 HTTPS와 동일 출처 API 프록시가 필요합니다.
4. 앱 권한은 `site-info:read`, `site-info:write`, `product:read`, `product:write`, `order:read`입니다. `site-info:write`는 앱 연동 완료 처리에 사용합니다. 일반 API와 프론트엔드를 재시작합니다. 관리자 API는 같은 DB의 IMWEB 연결 조회·재인증 지정에 새 enum을 사용하므로 함께 업데이트합니다.
5. 아임웹 앱스토어에서 앱을 사이트에 추가하고 접근 권한에 동의합니다. 개발 중인 앱은 개발자센터의 **앱 테스트**에서 소유한 테스트 사이트를 선택하고 동의합니다. 아임웹이 등록된 서비스 URL로 `siteCode`를 자동 전달하면, 서비스 로그인 → 소유한 워크스페이스 선택 → **아임웹으로 연결** 순서로 OAuth 인증을 완료합니다. 사이트 코드를 사용자가 입력하지 않습니다. 인증 후 스토어를 전체 선택하거나 필요한 항목만 선택해 **저장하고 성과 보기**를 누릅니다. 재인증이 필요한 기존 연결은 **아임웹으로 다시 연결** 버튼으로 저장된 사이트 정보를 사용합니다.

아임웹은 OAuth 인가 요청에 `siteCode`를 필수로 요구합니다. 이 값은 인증 수단이 아니라 연결할 사이트를 지정하는 식별자이며, 실제 권한은 OAuth 동의와 인가 코드 교환으로 확인합니다. 서비스 화면만 열어 사이트 선택 없이 인가 요청을 시작하는 방식은 지원하지 않습니다. [OAuth 명세](https://developers-docs.imweb.me/guide/개발-가이드-확인하기/oauth-2.0), [앱 연동 절차](https://developers-docs.imweb.me/guide/앱-연동하기)를 참고하세요.

앱 심사 전에는 개발자센터에 연동된 계정이 소유한 테스트 사이트 등 아임웹이 허용한 범위에서 테스트합니다. Client ID·Secret만으로 모든 고객 사이트에 접근할 수 있는 것은 아닙니다. 공개 배포 조건은 [아임웹 개발자 문서](https://developers-docs.imweb.me/)의 앱 준비·앱 연동·주의 및 제한사항을 확인합니다.

### ngrok으로 로컬 OAuth 테스트

프론트엔드 `3400`은 화면과 `/api`, `/open-api` 백엔드 프록시를 함께 제공합니다. `ngrok http 3400`으로 연결하고 일반 API는 기존 `8480`에서 실행합니다. 아래 `your-domain.ngrok-free.dev`는 실제 발급 주소로 바꿉니다.

| 설정 | 값 |
| --- | --- |
| 개발자센터 리다이렉트 URL / `app.imweb.redirect-uri` | `https://your-domain.ngrok-free.dev/open-api/platform-connections/imweb/callback` |
| 개발자센터 서비스 URL | `https://your-domain.ngrok-free.dev/settings/integrations/imweb/connect` |
| `app.imweb.frontend-redirect-uri` | `https://your-domain.ngrok-free.dev/settings/integrations/imweb/callback` |

`ad-frontend/.env.local`에 `DEV_SERVER_ALLOWED_HOSTS=your-domain.ngrok-free.dev`를 추가하면 Vite가 해당 도메인을 허용합니다. 여러 개발 도메인은 쉼표로 구분합니다. 도메인만 입력하며 프로토콜과 경로는 붙이지 않습니다. 백엔드 리다이렉션 설정과 프론트엔드를 재시작한 뒤, ngrok HTTPS 주소에 접속해 로그인하고 아임웹 연결을 시작합니다. OAuth state 쿠키가 같은 도메인에서 발급되고 돌아와야 하므로 localhost에서 시작하지 않습니다. 터널 도메인이 바뀌면 개발자센터, 백엔드의 두 리다이렉트 주소, 프론트엔드 허용 도메인을 함께 변경합니다.

## API

기존 서비스 Bearer Access Token과 워크스페이스 권한을 사용합니다. JSON은 snake_case, 응답 본문은 `Api.body`입니다.

공통 경로 `/api/workspaces/{workspaceId}`:

| 메서드·경로 | 기능 |
| --- | --- |
| `GET /connections/imweb/capabilities` | 서버 연결 준비 여부 |
| `POST /connections/imweb/authorize` | `{ "site_code": "S..." }`로 OAuth 시작 주소 발급. 소유자 전용 |
| `GET /connections/{connectionId}/imweb/units` | 인증된 사이트의 스토어 목록과 저장 여부 |
| `POST /connections/{connectionId}/imweb/units` | `{ "unit_codes": ["u..."] }`로 선택한 스토어 저장 |
| `GET /imweb/stores` | 저장된 스토어 목록 |
| `GET /imweb/stores/{assetId}/products?page=1&size=20` | 판매 중 상품 목록 |
| `GET /imweb/stores/{assetId}/sales?since=2026-09-01&until=2026-09-29` | 기간 판매 성과 |
| `GET /imweb/stores/{assetId}/product-options` | 카테고리·통화·상품 등록 지원 여부 |
| `POST /imweb/stores/{assetId}/products` | 검토한 상품 등록 |

`assetId`는 저장된 스토어의 내부 자산 ID입니다. 외부 `siteCode`·`unitCode`·토큰을 브라우저가 상품/성과 요청마다 지정하지 않습니다. 콜백은 `/open-api/platform-connections/imweb/callback`이며 일회용 state, 요청 브라우저의 HttpOnly 쿠키와 현재 소유자 권한을 검증합니다. 인증 후 앱 연동 완료 API를 호출하고 실제 사이트 코드가 요청과 같은지 확인한 뒤 저장합니다.

스토어 선택은 기존 Meta·네이버 자산 저장과 같은 추가 저장 방식입니다. 이미 저장한 스토어는 유지하며, 새로 선택한 항목을 추가하고 저장한 항목의 이름·통화·주소를 갱신합니다. 목록에 없는 스토어를 삭제하는 요청이 아니므로 화면에서도 저장된 체크 항목은 해제하지 않습니다.

토큰은 만료 전에 갱신하며 갱신 호출은 Redis lease로 직렬화합니다. 원격 읽기에서 인증 만료가 확인되면 한 번만 갱신 후 다시 읽습니다. 갱신 결과가 불확실하면 같은 refresh token을 재전송하지 않고 재연결을 요구합니다. 쓰기 요청에는 자동 재시도를 적용하지 않습니다.

## 성과 기준

최대 31일, Asia/Seoul 기준 **주문 생성일**로 주문 전체를 조회하고 현재 결제·환불 완료 금액을 집계합니다. 결제일별 매출, 정산액, 네이버 성과 집계 기준과 다릅니다.

- 결제액: 해당 기간 생성 주문의 현재 `totalPaymentPrice` 합계
- 환불액: `totalRefundedPrice` 합계. 환불 예정 금액은 차감하지 않습니다.
- 환불 차감액: 결제액 − 환불 완료액
- 결제 주문 수: 결제 금액이 있거나 무료 결제를 포함해 결제 완료가 확인되는 주문 수
- 평균 주문 금액: 결제액 ÷ 결제 주문 수
- 일별 표: 주문 생성일별 같은 지표. 조회된 주문이 없는 날짜는 0으로 표시합니다.

저장된 스토어의 unitCode로 주문을 한정하고 통화를 확인합니다. 통화를 섞어 합산하지 않습니다. 누락된 필수 금액·잘못된 응답·중복 주문·조회 중 총 건수 변경은 오류로 처리하며 일부 페이지의 합계를 전체 성과로 표시하지 않습니다. 최대 2,000건 또는 45초를 넘는 조회는 기간 축소를 안내합니다. 외부 한 요청의 타임아웃 때문에 실제 응답 종료까지는 이보다 더 걸릴 수 있습니다. 고객 이름·주소·연락처는 화면 응답이나 DB에 저장하지 않습니다.

## 상품 등록과 AI 상세페이지

현재 **단일 언어 스토어 사이트**의 일반 상품 등록을 지원합니다. 여러 언어 스토어가 있는 사이트는 상품과 성과 조회가 가능하며, 상품 등록은 아임웹 관리자에서 진행하도록 안내합니다. 옵션 상품·주문 변경·상품 수정은 이번 등록 화면 범위에 포함하지 않습니다.

`multipart/form-data`의 `request` 파트는 `application/json`, 추가 이미지 파트 이름은 반복되는 `files`입니다.

```json
{
  "name": "데일리 머그컵",
  "category_code": "카테고리 조회 응답의 code",
  "sale_price": 9900,
  "original_price": 12000,
  "stock_quantity": 10,
  "detail_content": "사용자가 입력한 일반 텍스트",
  "studio_output_id": 123
}
```

`studio_output_id`는 선택 사항입니다. 없으면 상세 설명과 상품 이미지가 필요합니다. 있으면 같은 워크스페이스의 완료된 `DETAIL_PAGE` 결과만 서버에서 불러옵니다. 추가 설명은 생략할 수 있으며 임의 HTML은 허용하지 않고 항상 이스케이프합니다. 광고 이미지(`AD_IMAGE`)는 Meta 전용으로 유지합니다.

이미지는 JPEG/PNG, 한 장 10MiB, 합계 20MiB, AI 이미지 포함 1~10장입니다. AI 이미지도 서버 저장본을 직접 사용합니다. 이미지는 아임웹으로 바이너리 업로드하며 만료되는 S3 주소를 상품에 저장하지 않습니다.

등록은 판매 중지 상태(`nosale`)로 상품을 만든 뒤 AI 이미지의 아임웹 영구 주소를 조회해 상세 HTML에 적용하고, 마지막에 판매 상태(`sale`)로 변경합니다. AI 결과를 사용하면 AI 이미지 한 장만 먼저 업로드해 주소를 확정하고, 추가 사진은 상세 적용 후 업로드합니다. 쿠폰·포인트·회원등급 할인은 기본 미적용이며 배송 안내는 사이트 템플릿을 사용합니다. 화면에서 가격·수량·상세 내용과 해당 기본값을 검토한 후 등록합니다.

- `CREATED`: 생성·필요한 상세 적용·판매 상태 변경을 확인했습니다.
- `DETAIL_PENDING`: 상품 ID는 확인했지만 이미지·상세 적용·판매 상태 변경 중 일부를 확정하지 못했습니다. 응답의 상품 ID를 보존하고 아임웹 관리자에서 이어서 확인합니다. 같은 상품을 다시 생성하지 않습니다.
- 생성 응답 자체가 불확실하면 결과 확인이 필요한 오류를 반환합니다. 먼저 아임웹 관리자에서 생성 여부를 확인해야 합니다. 실패 응답만으로 상품이 없다고 판단하지 않습니다.

`detail_applied`는 저장된 AI 상세 HTML 적용 여부입니다. 일반 상품이 정상 등록되어도 `false`이며, AI 상세 적용 이후 이미지 추가·판매 상태 확인이 실패하면 `DETAIL_PENDING`이면서 `true`일 수 있습니다. 판매 상태 변경이 불확실한 상품을 반드시 판매 중지 상태라고 표시하지 않습니다.

제품 생성·수정·판매 상태 변경은 자동 재전송하지 않습니다. 후속 호출 전에도 현재 회원·자산·토큰 권한을 검사합니다. 현재 기술 지원 세션에는 아임웹 경로가 허용되지 않습니다.

## 검증 범위

모의 HTTP, OAuth 쿠키/state, 권한 회수, 토큰 회전, 응답 범위·형식, 금액·페이지 완결성, 상품의 단계별 부분 성공 및 AI 결과 유형 검증을 테스트합니다. 프론트엔드는 정적 검사·빌드 및 모의 API 브라우저 검증을 수행합니다. 실제 아임웹 앱 인증·상품 등록과 SQL 적용은 개발 검증에서 실행하지 않았습니다.

프로토콜은 [공식 OAuth 문서](https://developers-docs.imweb.me/guide/%EA%B0%9C%EB%B0%9C-%EA%B0%80%EC%9D%B4%EB%93%9C-%ED%99%95%EC%9D%B8%ED%95%98%EA%B8%B0/oauth-2.0)와 [공식 OpenAPI 문서](https://developers-docs.imweb.me/)를 기준으로 구현했습니다.
