# 메타 캠페인·광고세트·광고 등록

저장된 Meta 광고 계정에 캠페인, 광고세트, 단일 이미지 소재, 실제 광고를 순서대로 생성한다. 캠페인·광고세트·광고의 상태는 모두 `PAUSED`다. 워크스페이스 소유자와 참여 완료 멤버가 저장된 공유 토큰으로 사용할 수 있다.

웹사이트로 연결하는 Facebook 피드 이미지 광고를 지원한다. 저장된 Instagram 프로필을 함께 선택하면 Instagram 피드도 포함한다. 트래픽(`OUTCOME_TRAFFIC`)과 판매(`OUTCOME_SALES`) 목표를 지원하고, 판매는 픽셀의 `PURCHASE` 이벤트로 최적화한다.

## 준비

1. [기존 Meta 연결 API](platform-connections.md)로 계정을 연결한다. 저장된 토큰에 `ads_management` 권한이 필요하다.
2. 사용할 광고 계정을 자산 선택 API로 저장한 뒤, 아래 광고 계정별 페이지 API로 사용 가능한 Facebook 페이지를 조회·저장한다. Instagram 게재 시 해당 페이지에 연결된 Instagram 프로필도 자산 선택 API로 저장한다.
3. 광고 계정·페이지·Instagram 프로필은 같은 워크스페이스의 **동일한 Meta 연결**에서 선택한 자산이어야 한다.
4. 아래 이미지 업로드 API로 JPEG/PNG를 S3에 저장하거나, Meta가 접근할 수 있는 이미지 HTTPS URL을 준비한다. 랜딩 페이지는 HTTPS URL을 사용한다. 판매 목표는 해당 광고 계정에서 사용할 수 있는 픽셀 ID와 구매 전환 추적 설정이 필요하다.

Meta는 생성 요청 시 계정·페이지·픽셀의 실제 광고 권한과 이미지·타겟·예산 등의 조건을 추가로 검증한다. 앱 검수나 Meta 비즈니스 자산 권한은 해당 계정에 맞게 설정되어 있어야 한다.

새로운 SQL이나 환경변수는 필요하지 않다. 이미지 저장은 `application-local.yml`·`application-prod.yml`의 기존 S3 설정을 사용한다. 광고 생성은 기존 암호화 토큰을 사용하고, 새 광고 객체는 Meta에 생성한다. 서비스 DB에 캠페인·광고의 사본을 저장하는 기능은 포함하지 않는다.

## 광고 소재 이미지 업로드

`POST /api/workspaces/{workspaceId}/meta/ad-accounts/{assetId}/images`

서비스 Access Token으로 인증하고 `multipart/form-data`의 `file` 필드에 이미지 파일을 전송한다. JSON 요청이 아니다. 브라우저에서 `FormData`를 사용할 때는 브라우저가 boundary를 포함하도록 `Content-Type` 헤더를 직접 지정하지 않는다.

```javascript
const form = new FormData();
form.append("file", selectedFile);
const response = await fetch(`/api/workspaces/${workspaceId}/meta/ad-accounts/${assetId}/images`, {
  method: "POST",
  headers: { Authorization: `Bearer ${accessToken}` },
  body: form
});
const uploaded = await response.json();
// uploaded.body.image_url: 미리보기, uploaded.body.image_key: 광고 등록용
```

성공 시 `Api.body`:

```json
{
  "image_key": "auto-threads/workspaces/1/meta/ad-accounts/10/images/ef0b4059-13b1-4eb3-958c-ea5636689c10.jpg",
  "image_url": "https://example-bucket.s3.ap-northeast-2.amazonaws.com/...?...",
  "expires_at": "2026-09-22T10:00:00Z",
  "content_type": "image/jpeg",
  "size": 245678
}
```

- JPEG(`image/jpeg`)·PNG(`image/png`), 최대 **10MiB**, 가로·세로 각각 최대 **10,000픽셀**, 총 **2,500만 픽셀**을 허용한다. 실제 이미지 형식과 디코딩 결과도 확인한다.
- 워크스페이스 참여 멤버이며 선택한 광고 계정에 유효한 `ads_management` 토큰이 있어야 한다. 같은 워크스페이스의 멤버는 같은 광고 계정에 업로드한 `image_key`를 재사용할 수 있다.
- 저장 키는 `{app.aws.s3.key-prefix}/workspaces/{workspaceId}/meta/ad-accounts/{assetId}/images/{UUID}.jpg|png`다. 원본 파일명은 경로로 사용하지 않는다.
- `image_url`은 **1시간 동안 유효한 서명 URL**이다. 광고 생성에는 만료되는 URL 대신 `ad.image_key`를 보낸다. 서버가 같은 워크스페이스·광고 계정의 이미지인지와 S3 객체 상태를 확인한 뒤 새로운 URL을 발급하여 Meta에 전달한다. 다른 워크스페이스·광고 계정의 키는 거부한다.
- 이미지 키 검증이나 S3 확인이 실패하면 캠페인 생성 전에 중단한다. 이미지 목록·삭제 API나 DB 메타데이터 저장은 추가하지 않는다.

S3 설정은 기존 `app.aws.s3.bucket`, `app.aws.s3.key-prefix`, `cloud.aws.region.static`, `cloud.aws.credentials.access-key`, `cloud.aws.credentials.secret-key`를 사용한다. 서버 IAM 계정에 해당 이미지 경로의 `s3:PutObject`와 `s3:GetObject` 권한이 필요하다. [HeadObject도 `s3:GetObject` 권한을 사용한다](https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadObject.html). 버킷은 비공개로 유지하고 public ACL을 설정하지 않는다. 브라우저는 백엔드로 업로드하므로 S3 업로드용 CORS 설정은 필요하지 않다.

[AWS 서명 URL 문서](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html)와 [S3 객체 소유권·ACL 문서](https://docs.aws.amazon.com/AmazonS3/latest/userguide/about-object-ownership.html)를 참고했다. 서명 URL에는 임시 접근 권한이 포함되므로 로그에 남기지 않는다.

## 광고 계정별 페이지 조회·저장

`GET /api/workspaces/{workspaceId}/meta/ad-accounts/{assetId}/pages`

`assetId`는 저장된 광고 계정의 내부 `assets[].id`다. 서버는 해당 계정의 Meta 연결 토큰으로 `/{act_광고계정ID}/promote_pages?fields=id,name`을 조회한다. 로그인 사용자의 `/me/accounts` 목록이나 이전에 저장한 페이지 목록으로 대체하지 않는다. 응답 `Api.body`는 다음 형태의 배열이다.

```json
[
  {
    "external_id": "123456789012345",
    "name": "브랜드 페이지",
    "platform_type": "FACEBOOK",
    "asset_type": "PAGE",
    "facebook_page_id": "123456789012345"
  }
]
```

`POST /api/workspaces/{workspaceId}/meta/ad-accounts/{assetId}/pages`

```json
{ "external_id": "123456789012345" }
```

서버가 해당 광고 계정의 `promote_pages`를 다시 조회하여 선택한 페이지의 현재 사용 가능 여부를 확인한다. 페이지 이름과 연결 정보는 Meta 응답으로 채워 광고 계정과 **동일한 Meta 연결**에 추가·갱신한다. 기존 광고 계정·페이지·Instagram 자산은 유지한다. 응답 `Api.body`는 해당 연결의 저장된 전체 자산 배열이며, 페이지의 내부 `id`를 광고 등록의 `page_asset_id`로 사용한다. 페이지의 Meta `external_id`를 `page_asset_id`로 전달하지 않는다.

조회·저장에는 워크스페이스 멤버 권한과 유효한 `ads_management` 토큰이 필요하다. Meta 조회 후 계정·연결·토큰·관리 권한을 다시 확인하며 저장 과정에서도 멤버 권한과 토큰을 확인한다. 목록이 비어 있으면 광고 계정의 페이지 할당과 연결 사용자의 페이지 접근 권한을 확인한다. 토큰 오류는 재인증하고, 권한 오류는 Meta 자산·앱 권한 설정과 연결 동의를 확인한다. 권한 오류를 빈 목록으로 숨기지 않는다.

프런트엔드는 광고 계정을 바꿀 때 페이지 목록·선택을 초기화하고 새 계정의 목록을 조회한다. 저장한 페이지도 현재 목록에 포함된 경우에만 사용한다. 광고 등록 요청 시에도 선택 페이지가 현재 `promote_pages`에 있는지 첫 캠페인 생성 전에 확인한다. 이 검증이 실패하면 Meta 광고 객체를 생성하지 않는다.

## 전체 광고 생성

`POST /api/workspaces/{workspaceId}/meta/ad-accounts/{assetId}/ads`

서비스 Access Token 인증이 필요하다. `assetId`, `page_asset_id`, `instagram_asset_id`는 기존 연결 조회 응답의 `assets[].id`이며 Meta 외부 ID가 아니다. `pixel_id`는 Meta의 픽셀 ID를 사용한다. 요청·응답은 snake_case다.

```json
{
  "campaign": {
    "name": "가을 상품 판매",
    "objective": "OUTCOME_SALES",
    "special_ad_categories": []
  },
  "ad_set": {
    "name": "한국 성인 피드",
    "daily_budget": 10000,
    "countries": ["KR"],
    "age_min": 18,
    "age_max": 65,
    "pixel_id": "123456789012345"
  },
  "ad": {
    "name": "가을 상품 이미지 광고",
    "page_asset_id": 11,
    "instagram_asset_id": 12,
    "image_key": "auto-threads/workspaces/1/meta/ad-accounts/10/images/ef0b4059-13b1-4eb3-958c-ea5636689c10.jpg",
    "link_url": "https://shop.example.com/products/autumn?utm_source=meta",
    "message": "가을 신상품을 만나보세요.",
    "headline": "가을 신상품 출시",
    "description": "상품 상세 정보와 혜택을 확인하세요.",
    "call_to_action": "SHOP_NOW"
  }
}
```

업로드 응답의 `image_key`를 그대로 사용한다. 기존 외부 이미지 주소를 사용하려면 `image_key` 대신 `image_url`을 보낸다. 두 필드 중 정확히 하나가 필요하며 둘 다 보내면 거부한다. 위 키 예시는 워크스페이스 `1`·광고 계정 자산 `10`에 대한 요청이다.

트래픽 목표는 `objective`를 `OUTCOME_TRAFFIC`으로 지정하고 `pixel_id`를 생략한다. `instagram_asset_id`를 생략하면 Facebook 피드만 사용한다. `description`도 생략할 수 있다.

| 입력 | 의미·검증 |
| --- | --- |
| `campaign.special_ad_categories` | 필수 배열. 해당 없음은 `[]`. `CREDIT`, `EMPLOYMENT`, `FINANCIAL_PRODUCTS_SERVICES`, `HOUSING`, `ISSUES_ELECTIONS_POLITICS`, `ONLINE_GAMBLING_AND_GAMING` 지원 |
| `campaign.special_ad_category_country` | 특별 광고 카테고리 신고에 필요한 국가코드 배열. 필요에 따라 지정하며 실제 게재 국가는 `ad_set.countries`로 지정 |
| `ad_set.daily_budget` | 양의 정수. Meta가 요구하는 광고 계정 통화의 최소 단위로 전달하며 서버에서 환율·금액 단위를 변환하지 않음 |
| `ad_set.countries` | 대문자 2자리 국가코드, 1~25개. 중복은 제거 |
| `ad_set.age_min`, `age_max` | 필수, 18~65, 최소 연령 ≤ 최대 연령. 카테고리·국가별 허용 타겟은 Meta에서 검증 |
| `ad_set.pixel_id` | 판매 목표에 필수인 숫자 ID. 트래픽 목표에는 지정하지 않음 |
| 이름·`ad.headline` | 공백만인 값 불가, 서비스 입력 제한 255자 |
| `ad.message`, `description` | 본문 최대 5,000자, 선택 설명 최대 255자 |
| `ad.image_key` | 업로드 응답의 키, 최대 1,024자. 요청 워크스페이스·광고 계정 경로 및 S3 객체를 확인하고 새 서명 URL을 발급. `image_url`과 동시 입력 불가 |
| `ad.image_url`, `link_url` | 최대 2,048자 HTTPS 주소. 사용자 정보·프래그먼트·명백한 로컬 주소는 거부. 외부 `image_url`은 서버가 다운로드하지 않고 Meta에 전달하므로 실제 접근 가능 여부는 Meta에서 확인. `image_key`를 사용하면 `image_url` 생략 |
| `ad.call_to_action` | `LEARN_MORE`, `SHOP_NOW`, `SIGN_UP`, `CONTACT_US`, `BOOK_TRAVEL`, `DOWNLOAD`, `GET_QUOTE`, `APPLY_NOW`, `GET_OFFER` |

광고 세트는 `AUCTION` 캠페인에서 일일 예산, `IMPRESSIONS` 과금, `LOWEST_COST_WITHOUT_CAP` 입찰을 사용한다. 트래픽 최적화는 `LINK_CLICKS`, 판매 최적화는 `OFFSITE_CONVERSIONS`다. 국가·연령과 Facebook 피드, 선택적 Instagram 피드 위치를 명시하며 타겟 자동 확장은 끈다. 이미지 규격과 링크·CTA 조합은 Meta가 검증한다.

성공 시 `Api.body`:

```json
{
  "asset_id": 10,
  "ad_account_id": "act_123456",
  "campaign_id": "120000000001",
  "ad_set_id": "120000000002",
  "creative_id": "120000000003",
  "ad_id": "120000000004",
  "status": "CREATED",
  "failed_step": null,
  "message": "캠페인·광고세트·광고를 일시정지 상태로 생성했습니다."
}
```

응답의 `status`는 생성 작업 결과이며, Meta에 만들어진 객체 상태는 `PAUSED`다. 생성된 캠페인은 기존 캠페인 목록 API로 확인할 수 있다. 활성화와 이름·일 예산 변경은 [광고 수정 API](meta-ad-update.md)를 사용한다. 영상·캐러셀 광고는 이 생성 API의 범위에 포함하지 않는다.

## 중간 실패 처리

생성 순서는 `CAMPAIGN → AD_SET → CREATIVE → AD`다. 각 외부 쓰기 전 워크스페이스 멤버 권한, 토큰, 페이지·Instagram 연결을 다시 확인한다. 이후 단계에서 실패하면 이미 생성한 객체는 일시정지 상태로 남고, 응답의 `body`에 알고 있는 ID를 보존한다. 별도 DB 트랜잭션으로 Meta의 생성 작업을 롤백할 수는 없다.

예를 들어 광고세트 생성이 거절되면 HTTP 오류 응답과 함께 다음과 같은 `body`를 반환한다:

```json
{
  "asset_id": 10,
  "ad_account_id": "act_123456",
  "campaign_id": "120000000001",
  "ad_set_id": null,
  "creative_id": null,
  "ad_id": null,
  "status": "FAILED",
  "failed_step": "AD_SET",
  "message": "Meta 광고 등록 요청이 거절되었습니다. 계정 권한과 입력 정보를 확인해 주세요."
}
```

`FAILED`는 해당 단계가 거절되었거나 권한·연결 검사에서 중단된 상태다. 타임아웃·서버 오류·잘못된 성공 응답 등으로 생성 여부를 확정할 수 없으면 `UNKNOWN`으로 반환한다. 이때 실패 단계의 ID가 `null`이어도 Meta에서 생성되지 않았다고 단정할 수 없다. 오류 응답에서도 `body`의 ID와 `failed_step`을 프런트엔드에 보존하고, 재요청 전 Meta 광고 관리자에서 결과를 확인한다.

자동 재시도·부분 생성 객체의 자동 삭제·중단 지점부터 이어 생성하는 기능은 없다. 같은 전체 요청을 다시 보내면 새로운 캠페인부터 생성하며, 동일 이름으로도 여러 객체가 만들어질 수 있다. 모든 단계가 성공하면 추가 원격 조회 없이 생성 ID를 반환한다.

## 캠페인만 생성

`POST /api/workspaces/{workspaceId}/meta/ad-accounts/{assetId}/campaigns`

```json
{
  "name": "가을 캠페인",
  "objective": "OUTCOME_TRAFFIC",
  "special_ad_categories": []
}
```

응답 `body`는 `{ "id": "120000000001" }`이다. 캠페인만 생성하는 API는 `OUTCOME_AWARENESS`, `OUTCOME_TRAFFIC`, `OUTCOME_ENGAGEMENT`, `OUTCOME_LEADS`, `OUTCOME_APP_PROMOTION`, `OUTCOME_SALES`를 허용한다. 상태는 `PAUSED`, 구매 유형은 `AUCTION`, 캠페인 예산 공유는 꺼진 상태다. 앱별 추가 설정 등 목표의 세부 요구사항은 Meta가 검증한다.

## 검증 및 공식 참고

모의 HTTP로 실제 생성 경로·폼·타겟·이미지 소재·PAUSED 상태와 오류 처리를 검증한다. 서비스·비즈니스·MVC 테스트는 계정과 페이지 권한, 입력 검증, 단계별 ID 전달, 부분 실패와 응답 형식을 검증한다. 구현·테스트 중 실제 Meta 광고는 생성하지 않았으며 실제 앱 권한과 자산에 대한 연동 검증은 별도로 필요하다.

공식 요청 형식은 [캠페인 생성 예제](https://github.com/facebook/facebook-python-business-sdk/blob/main/examples/AdAccountCampaignsPost.py), [AdSet](https://github.com/facebook/facebook-python-business-sdk/blob/main/facebook_business/adobjects/adset.py), [이미지 링크 소재](https://github.com/facebook/facebook-python-business-sdk/blob/main/facebook_business/adobjects/adcreativelinkdata.py), [페이지·Instagram 소재 설정](https://github.com/facebook/facebook-python-business-sdk/blob/main/facebook_business/adobjects/adcreativeobjectstoryspec.py), [광고 생성 예제](https://github.com/facebook/facebook-python-business-sdk/blob/main/examples/AdAccountAdsPost.py)를 참고했다.
