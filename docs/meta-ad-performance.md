# 메타 캠페인 및 광고 성과

워크스페이스에 선택·저장한 Meta 광고 계정으로 캠페인과 기간별 성과를 조회한다. 워크스페이스 소유자와 참여를 완료한 멤버가 사용할 수 있다. 기존 연결의 암호화된 토큰을 사용하며 새로운 환경변수나 SQL은 필요하지 않다. 조회 결과를 별도 테이블에 적재하지 않고 요청 시 Meta에서 가져온다.

## API

모든 요청은 서비스의 Access Token 인증이 필요하다. 응답은 기존 `Api` 형식으로 감싸며 아래 설명과 예시는 `body` 내부 구조다. JSON은 snake_case다.

| 메서드 | 경로 | 기능 |
| --- | --- | --- |
| GET | `/api/workspaces/{workspaceId}/meta/ad-accounts/{assetId}/campaigns` | 저장한 광고 계정의 캠페인 목록 |
| GET | `/api/workspaces/{workspaceId}/meta/ad-accounts/{assetId}/insights?since=2026-09-01&until=2026-09-21` | 광고 계정 합계·일평균과 캠페인별 성과 |
| GET | `/api/workspaces/{workspaceId}/meta/insights?since=2026-09-01&until=2026-09-21` | 워크스페이스 전체 저장 광고 계정의 합계·일평균 |

`assetId`는 기존 `GET /api/workspaces/{workspaceId}/connections` 응답의 `assets[].id`다. 그중 `platform_type = FACEBOOK`, `asset_type = AD_ACCOUNT`인 자산을 사용한다. Meta 외부 ID인 `act_123456`이나 연결 ID를 경로의 `assetId`로 보내지 않는다. 아직 저장하지 않은 광고 계정은 기존 [자산 선택 API](platform-connections.md)로 먼저 저장한다.

`since`, `until`은 성과 조회에 모두 필수이며 `YYYY-MM-DD` 형식이다. 시작일·종료일을 모두 포함하고 최대 366일을 요청할 수 있다. 날짜 누락, 잘못된 형식, 시작일보다 앞선 종료일은 400 응답이다. Meta 조회 기간은 각 광고 계정의 시간대를 따르며 `timezone_name`을 함께 반환한다. 서로 다른 시간대의 계정도 각자의 현지 날짜 범위를 기준으로 합산하므로 동일한 UTC 시간 구간을 의미하지는 않는다.

캠페인 목록:

```json
[
  {
    "id": "120000000001",
    "name": "가을 프로모션",
    "status": "ACTIVE",
    "effective_status": "ACTIVE",
    "objective": "OUTCOME_SALES"
  }
]
```

캠페인 목록은 Meta가 해당 계정에 반환하는 접근 가능한 목록을 페이지 끝까지 조회한다. 캠페인 성과는 선택 기간에 Meta Insights가 반환한 캠페인만 포함하며 활동이 없는 캠페인은 목록에 없을 수 있다. 계정 전체 합계는 별도의 account 수준 Insights로 조회하므로 화면에 표시된 캠페인 성과 행의 합계와 다를 수 있다.

광고 계정 성과 응답은 `since`, `until`, `days`, `account`, `campaigns`로 구성한다. `account`는 아래 전체 성과의 `accounts[]`와 같고, `campaigns[]`에는 `campaign_id`, `campaign_name`, `metrics`, `daily_average`가 들어간다.

워크스페이스 전체 성과 예시:

```json
{
  "since": "2026-09-01",
  "until": "2026-09-02",
  "days": 2,
  "account_count": 1,
  "accounts": [
    {
      "asset_id": 10,
      "connection_id": 3,
      "ad_account_id": "act_123456",
      "name": "브랜드 광고 계정",
      "currency": "KRW",
      "timezone_name": "Asia/Seoul",
      "metrics": {
        "spend": 10000,
        "impressions": 2000,
        "clicks": 100,
        "ctr": 5.000000,
        "cpc": 100.000000,
        "cpm": 5000.000000,
        "purchase_value": 35000,
        "roas": 3.500000
      },
      "daily_average": {
        "spend": 5000.000000,
        "impressions": 1000.000000,
        "clicks": 50.000000
      }
    }
  ],
  "totals_by_currency": [
    {
      "currency": "KRW",
      "account_count": 1,
      "metrics": {
        "spend": 10000,
        "impressions": 2000,
        "clicks": 100,
        "ctr": 5.000000,
        "cpc": 100.000000,
        "cpm": 5000.000000,
        "purchase_value": 35000,
        "roas": 3.500000
      },
      "daily_average": {
        "spend": 5000.000000,
        "impressions": 1000.000000,
        "clicks": 50.000000
      }
    }
  ]
}
```

## 집계 기준

- `spend`: Meta가 반환한 광고 계정 통화의 지출액. 환율 변환하지 않는다.
- `impressions`: 노출 수. `clicks`: Meta의 전체 클릭 수이며 링크 클릭만을 의미하지 않는다.
- `ctr`: `총 클릭 / 총 노출 × 100` (%).
- `cpc`: `총 지출 / 총 클릭` (계정 통화).
- `cpm`: `총 지출 / 총 노출 × 1000` (계정 통화).
- `purchase_value`: Meta Insights의 `action_values`에서 가져온 구매 전환 금액 (계정 통화). `omni_purchase`를 우선 사용하고, 해당 항목이 없으면 `purchase`를 사용한다. 둘을 더하거나 웹·앱 등 하위 구매 금액을 추가하지 않는다.
- `roas`: `구매 전환 금액 / 지출`. 배수로 반환하며 `3.5`는 350%를 의미한다. 지출이 0이면 `null`이다. 캠페인·계정·통화별 전체 `metrics`에 동일하게 제공한다.
- `daily_average`: 지출·노출·클릭 합계를 조회 기간의 전체 일수로 나눈 값. 집행하지 않은 날도 분모에 포함한다.

평균과 비율은 소수점 6자리까지 표시하고 이후 자릿수는 반올림한다. 분모가 0인 CTR·CPC·CPM은 `null`이다. 기간 성과가 없는 계정은 지출·노출·클릭과 일평균을 0으로 반환한다.

전체 성과는 같은 워크스페이스에 저장된 **모든 Meta 광고 계정**을 대상으로 한다. 같은 `act_...` 광고 계정을 여러 Meta 사용자 연결에서 선택했더라도 한 번만 집계한다. 중복 중 사용 가능한 토큰이 있는 최근 연결을 선택하고, 모든 중복 연결이 만료되었다면 재연결 오류를 반환한다. 통화가 다르면 `totals_by_currency`에 통화별로 분리한다. 비율 지표는 계정별 비율의 산술평균이 아니라 해당 통화 그룹의 합계로 계산한다. 저장된 광고 계정이 없으면 `account_count: 0`, `accounts: []`, `totals_by_currency: []`다.

전체 ROAS도 해당 통화 그룹의 `구매 전환 금액 합계 / 지출 합계`로 계산한다. 계정·캠페인 ROAS를 더하거나 산술평균하지 않는다. 예를 들어 지출 100·구매 전환 금액 500인 계정과 지출 900·구매 전환 금액 1800인 계정의 전체 ROAS는 `(500 + 1800) / (100 + 900) = 2.3`이다.

구매 전환 금액은 Meta가 현재 요청의 기여 설정과 기간에 따라 보고한 값이며 쇼핑몰의 전체 매출과는 다르다. 구매 항목이 누락되었거나 비어 있으면 보고된 구매 전환 금액은 0으로 처리한다. 따라서 지출이 있고 구매 전환 금액이 없으면 `roas: 0`, 지출도 없으면 `roas: null`이다. 구매 추적이나 전환 가치가 설정되지 않은 경우에도 이 결과가 나올 수 있으므로 실제 구매가 없었다는 뜻으로 해석하지 않는다. `1d_view`·`7d_click` 같은 기여 기간별 값을 서로 더하지 않고 응답의 `value`를 사용한다. 전환 건수와 사용자 도달 수는 제공하지 않는다.

## 권한과 실패 처리

다른 워크스페이스의 자산, 저장하지 않은 계정, Facebook 페이지·Instagram 프로필·네이버 스토어는 광고 계정 경로로 조회할 수 없다. 연결이 삭제된 과거 자산도 제외한다. 초대 대기·추방된 사용자는 접근할 수 없다. 외부 조회가 끝난 뒤에도 멤버 권한과 저장 자산·토큰이 변경되지 않았는지 다시 확인한다. 토큰 원문은 응답에 포함하지 않는다.

기존 Meta OAuth의 `ads_read` 권한을 사용한다. 토큰이 만료되었거나 Meta가 접근을 거절하면 오류로 처리하며 소유자가 기존 연결 API로 재인증할 수 있다. 전체 조회 중 한 계정 또는 한 페이지라도 실패하면 전체 요청을 실패 처리한다. 조회 실패를 성과 0으로 취급하거나 일부 계정만으로 전체 합계를 반환하지 않는다.

Meta 페이지네이션은 고정 Graph API 주소에 커서를 전달해 순회한다. 각 목록은 최대 100페이지와 시간 제한을 적용하며 한도 초과 시 일부 결과를 반환하지 않는다. 전체 조회는 계정 수에 따라 원격 요청 수와 응답 시간이 증가한다.

## 검증 및 공식 참고

모의 HTTP로 캠페인·Insights 요청과 페이지 순회, 잘못된 수치와 계정 응답을 검증한다. 서비스·비즈니스·MVC 테스트로 워크스페이스 권한, 중복 계정, 통화별 합계, 비율·일평균, 날짜 검증을 확인한다. 실제 광고 계정에 대한 성과 조회는 운영 토큰과 앱 권한으로 별도 확인해야 한다.

요청 필드와 `campaigns`·`insights` 경로는 [Meta 공식 Business SDK AdAccount](https://github.com/facebook/facebook-python-business-sdk/blob/main/facebook_business/adobjects/adaccount.py), 성과 필드는 [Meta 공식 AdsInsights](https://github.com/facebook/facebook-python-business-sdk/blob/main/facebook_business/adobjects/adsinsights.py)를 기준으로 구현했다.
