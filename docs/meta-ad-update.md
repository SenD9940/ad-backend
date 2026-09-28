# 메타 광고 수정

저장된 워크스페이스 광고 계정의 광고·광고세트·캠페인 이름과 상태, 기존 일 예산을 수정한다. 서비스 Access Token 인증과 워크스페이스 멤버 권한, 연결 토큰의 `ads_management` 권한이 필요하다. 기존 토큰과 자산 정보를 사용하므로 SQL이나 환경변수 추가는 없다.

## API

공통 경로: `/api/workspaces/{workspaceId}/meta/ad-accounts/{assetId}`

| 메서드·경로 | 수정 가능한 필드 |
| --- | --- |
| `PATCH /ads/{adId}` | `name`, `status` |
| `PATCH /ad-sets/{adSetId}` | `name`, `status`, `daily_budget` |
| `PATCH /campaigns/{campaignId}` | `name`, `status`, `daily_budget` |

`assetId`는 저장된 광고 계정 자산의 **내부 ID**다. `adId`, `adSetId`, `campaignId`는 Meta 객체의 **외부 숫자 ID**이며, [광고 생성 응답](meta-ad-creation.md)의 `ad_id`, `ad_set_id`, `campaign_id`를 사용한다. 요청은 `Content-Type: application/json`이며 snake_case 필드명을 사용한다.

광고 끄기:

```http
PATCH /api/workspaces/1/meta/ad-accounts/10/ads/120000000004
Authorization: Bearer <서비스 Access Token>
Content-Type: application/json

{ "status": "PAUSED" }
```

광고 켜기:

```json
{ "status": "ACTIVE" }
```

광고세트 이름과 일 예산 수정:

```http
PATCH /api/workspaces/1/meta/ad-accounts/10/ad-sets/120000000002
Authorization: Bearer <서비스 Access Token>
Content-Type: application/json

{ "name": "한국 성인 피드", "daily_budget": 20000 }
```

캠페인 켜기:

```http
PATCH /api/workspaces/1/meta/ad-accounts/10/campaigns/120000000001
Authorization: Bearer <서비스 Access Token>
Content-Type: application/json

{ "status": "ACTIVE" }
```

성공 시 `Api.body`:

```json
{ "id": "120000000004", "success": true }
```

`id`는 수정한 객체의 ID다. `success: true`는 Meta가 수정 요청을 성공으로 응답했다는 의미이며, 광고 심사 완료나 실제 게재를 보장하는 상태 값은 아니다.

## 수정 규칙

- `status`는 `ACTIVE`와 `PAUSED`만 허용한다. `name`은 공백이 아닌 최대 255자다.
- 수정할 필드만 보낸다. 생략 또는 `null`인 필드는 Meta에 전달하지 않고 기존 값을 유지한다. 빈 요청과 지원하지 않는 필드는 거부한다.
- `daily_budget`은 양의 정수이며 광고 계정 통화에 대한 Meta 금액 단위를 그대로 사용한다. 통화 변환은 하지 않는다. 최소 예산과 실제 허용 범위는 Meta가 검증한다.
- 광고 자체에는 예산이 없다. 현재 생성 API로 만든 광고는 **광고세트**의 `daily_budget`을 수정한다. 같은 광고세트의 다른 광고도 그 예산을 공유한다.
- 이미 캠페인에서 일 예산을 관리하는 경우 캠페인 API로 수정한다. 캠페인이 예산을 관리하고 있으면 광고세트 예산 변경은 거부한다.
- 현재 일 예산이 설정된 객체의 금액만 수정한다. 총 예산(`lifetime_budget`)을 일 예산으로 바꾸거나, 광고세트 예산을 캠페인 예산으로 전환하는 기능은 제공하지 않는다. 해당 객체도 이름·상태만 수정하는 요청은 허용한다.
- 한 요청은 지정한 객체 하나만 수정한다. 광고를 `ACTIVE`로 바꿔도 상위 광고세트·캠페인이 `PAUSED`이면 게재되지 않는다. 생성 API는 세 객체를 모두 `PAUSED`로 생성하므로 처음 게재하려면 각 상태를 변경해야 한다. 상위 상태를 변경하면 그 아래의 다른 광고에도 영향을 준다.

## 권한과 실패 처리

쓰기 전에 Meta에서 대상 객체를 조회하여 요청 ID, 객체 유형과 `account_id`를 확인한다. 따라서 공유 토큰이 다른 광고 계정에도 접근할 수 있더라도 요청 경로의 저장 계정에 속하지 않은 객체는 수정하지 않는다. 광고세트의 예산을 수정할 때는 부모 캠페인의 예산도 조회한다. 원격 조회가 끝난 뒤 워크스페이스 멤버·계정·토큰·관리 권한을 다시 확인하고 쓰기를 수행한다.

Meta에는 `POST /{객체 ID}`로 지정한 필드만 전달한다. 다른 객체를 자동 활성화하거나, 수정 요청을 자동 재시도하거나, 광고 소재·타겟·픽셀·목표를 변경하지 않는다.

Meta 거절은 HTTP 오류로 반환한다. 타임아웃·서버 오류·해석할 수 없는 성공 응답처럼 변경 여부를 확정할 수 없는 경우에는 결과 확인이 필요하다는 오류를 반환한다. 이 경우 실패 응답만으로 값이 유지됐다고 단정하지 말고 Meta 광고 관리자에서 상태와 예산을 확인한다. 오류에는 공유 토큰이나 Meta 원본 오류 내용을 포함하지 않는다.

## 검증과 참고

모의 HTTP와 비즈니스·MVC 테스트로 권한, 대상 계정 검증, 생략 필드 보존, 예산 방식 검사, 요청 형식과 오류 처리를 검증한다. 개발 중 실제 광고 상태나 예산은 변경하지 않았다.

Meta 공식 SDK의 [Campaign](https://github.com/facebook/facebook-python-business-sdk/blob/main/facebook_business/adobjects/campaign.py), [AdSet](https://github.com/facebook/facebook-python-business-sdk/blob/main/facebook_business/adobjects/adset.py), [Ad](https://github.com/facebook/facebook-python-business-sdk/blob/main/facebook_business/adobjects/ad.py)에서 수정 필드와 상태를 확인했다. 예산 관리 방식의 전환을 거부하는 규칙은 이번 API의 지원 범위다.
