# 스마트스토어 상품 등록

저장된 스마트스토어 채널에 옵션 없는 새 실물 상품을 등록한다. 상품 목록의 **상품 등록**에서 별도 입력 화면을 열고, 상품·이미지·배송·고시 내용을 검토한 뒤 등록한다. 기존 `SELF`, `SELLER`, 솔루션 연결의 자격 증명을 사용하며 SQL이나 환경변수 추가는 없다. 네이버 앱의 상품 및 판매자 정보 API 권한이 필요하다.

## 지원 범위

- 상품명, 최종 카테고리, 판매가, 재고, 텍스트 상세 설명
- 대표 이미지 1장과 추가 이미지 최대 9장. JPEG/PNG, 장당 10MB·전체 20MB 이하
- 원산지, 수입사, 과세 구분, 미성년자 구매 가능 여부, A/S 정보
- 국내 출고지·반품지, 일반 택배, 무료·유료·조건부 무료 배송, 반품·교환 배송비
- 카테고리에 맞는 실물 상품정보제공고시 유형과 필수 항목
- 스마트스토어 전시 여부와 네이버 쇼핑 등록 여부

옵션·그룹상품, 중고·예약·디지털·대여 상품, 해외 출고, 인증서 등록과 특수 배송은 이번 입력 화면에서 지원하지 않는다. 별도 인증이나 필수 옵션이 필요한 상품은 스마트스토어센터에서 등록한다. 인증 면제나 상품 사실을 자동 입력하지 않는다.

원상품은 네이버 등록 API 규칙에 따라 `SALE`로 생성한다. 전시 상태 `ON`/`SUSPENSION`은 채널의 전시 여부이며 임시 저장 상태가 아니다. 재고는 1 이상이어야 한다. 상세 설명은 HTML 입력이 아닌 텍스트로 받아 서버에서 이스케이프하고 줄바꿈만 변환한다. [네이버 상품 등록 API](https://apicenter.commerce.naver.com/docs/commerce-api/current/create-product-product)

## 서비스 API

공통 경로: `/api/workspaces/{workspaceId}/naver/stores/{assetId}`. `assetId`는 저장 자산 내부 ID다. 서비스 Access Token과 워크스페이스 멤버 권한이 필요하다. 응답은 `Api.body`와 snake_case이며 `Cache-Control: no-store`를 설정한다.

| 메서드·경로 | 용도 |
| --- | --- |
| `GET /product-creation/options` | 최종 카테고리, 원산지 코드, 국내/해외 구분을 포함한 출고·반품 주소록, 지원 택배사 |
| `GET /product-creation/notices?categoryId={id}` | 해당 카테고리의 지원 고시 유형 및 필드 이름·입력 형식·필수 여부·길이·선택값 |
| `POST /products` | multipart `request` JSON과 반복 `images` 파일로 등록 |

등록 요청의 `request` 파트는 `Content-Type: application/json`이다. 이미지는 선택한 순서대로 `images` 파트를 추가하며 첫 파일이 대표 이미지다. JSON 필드는 아래와 같다.

```json
{
  "name": "사용자가 입력한 상품명",
  "category_id": "최종 카테고리 ID",
  "sale_price": 10000,
  "stock_quantity": 10,
  "detail_content": "사용자가 입력한 상품 상세 설명",
  "origin_area_code": "선택한 최종 원산지 코드",
  "tax_type": "TAX",
  "minor_purchasable": true,
  "after_service_telephone_number": "사용자의 실제 A/S 전화번호",
  "after_service_guide_content": "사용자의 실제 A/S 안내",
  "delivery_company": "CJGLS",
  "delivery_fee_type": "PAID",
  "delivery_fee": 3000,
  "shipping_address_id": "판매자 출고지 주소록 번호",
  "return_address_id": "판매자 반품지 주소록 번호",
  "return_delivery_fee": 3000,
  "exchange_delivery_fee": 6000,
  "notice_type": "선택한 고시 유형",
  "notice_fields": {},
  "display_status": "SUSPENSION",
  "naver_shopping_registration": false
}
```

예시는 입력 구조 설명용이며 실제 등록 데이터가 아니다. `notice_fields`에는 고시 조회 응답의 `key`를 사용하고 필수 항목을 모두 입력한다. 불리언은 JSON 불리언으로 전달한다. 수입 원산지는 `importer`, 원산지 직접 입력은 `origin_area_content`, 조건부 무료 배송은 `free_conditional_amount`가 추가로 필요하다.

성공:

```json
{
  "status": "CREATED",
  "origin_product_no": "원상품 번호",
  "smartstore_channel_product_no": "스마트스토어 상품 번호",
  "message": "스마트스토어에 상품을 등록했습니다."
}
```

상품 번호는 정밀도 손실을 막기 위해 문자열로 반환한다. 등록 결과가 불확실하면 `status: "UNKNOWN"`, 두 상품 번호는 `null`이며 스마트스토어센터에서 확인하라는 안내를 반환한다.

## 네이버 호출과 오류 처리

입력 검증과 채널·판매자 확인 후 네이버 `POST /v1/product-images/upload`로 이미지를 올리고, 반환받은 URL만 사용해 `POST /v2/products`를 한 번 호출한다. 네이버는 대표·추가 이미지에 전용 업로드 API의 URL을 요구하므로 임의 URL이나 S3 URL을 상품 이미지에 직접 전달하지 않는다. [네이버 이미지 API](https://apicenter.commerce.naver.com/docs/commerce-api/current/upload-product)

상품 등록 API는 스마트스토어 채널 번호를 받지 않는다. 서버는 저장 자산의 채널이 현재 판매자의 유일한 `STOREFARM` 채널인지 검증하여 다른 스토어에 등록하지 않도록 한다. 출고지·반품지도 현재 판매자의 주소록에 속하는 국내 주소인지 확인한다. 멤버 권한과 연결·토큰 변경 여부를 조회 전후, 이미지 업로드 전, 상품 생성 직전에 다시 검사한다.

메타데이터 조회는 기존 앱별 호출 간격·전체 45초 예산과 제한적 읽기 재시도를 사용한다. 토큰 갱신은 쓰기 전의 검증 단계에서만 한다. 이미지 업로드와 상품 생성은 자동 재시도하지 않으며, 같은 앱의 상품 등록 흐름을 프로세스 안에서 순차 처리해 이미지 업로드가 겹치지 않도록 한다. 여러 서버 인스턴스에 같은 앱을 배포할 때에는 공유 큐 또는 분산 잠금이 필요하다. [네이버 이미지 동시 호출 안내](https://github.com/commerce-api-naver/commerce-api/discussions/486)

상품 생성 타임아웃·서버 오류·해석할 수 없는 성공 응답은 실패로 단정하지 않는다. 화면은 재등록을 막고 판매자센터에서 기존 등록 여부를 확인하도록 한다. 네이버가 명시적으로 거절한 요청은 입력을 수정한 뒤 다시 제출할 수 있다. 이미지 업로드 뒤 상품 등록이 실패하면 네이버에 이미지가 남을 수 있으며, 이미지를 별도로 삭제하거나 상품을 자동 재생성하지 않는다.

토큰·시크릿·주소록 연락처·네이버 원본 오류는 로그와 오류 응답에 포함하지 않는다. 원격 입력 오류는 허용된 항목 이름으로 안내한다. 카테고리별 고시 필드는 네이버 공식 2.89.0 등록 스키마에서 추출한 `naver-product-notices.json`으로 검증하며, 조회 API가 허용한 유형만 선택할 수 있다.

고시 조회의 네이버 `categoryId`에는 대분류 ID가 필요하다. 서버가 전체 카테고리의 `wholeCategoryName`으로 선택한 최종 카테고리의 대분류를 찾고, 고시 조회와 검증에 그 ID를 사용한다. 상품 생성의 `leafCategoryId`에는 사용자가 선택한 최종 카테고리를 유지한다. 대응하는 대분류가 없거나 중복되면 임의의 유형을 추측하지 않고 조회 오류를 반환한다. [네이버 공식 대분류 조회 안내](https://github.com/commerce-api-naver/commerce-api/discussions/1733)

## 검증

모의 HTTP, 비즈니스·MVC 테스트로 요청 구조, 이미지 검증, 권한과 자산 관계, 입력 필드 검증, 원격 쓰기의 자동 재시도 방지, 불확실한 결과와 민감 정보 비노출을 확인한다. 브라우저에서는 모의 응답으로 입력·검토·등록·오류 흐름을 확인한다. 실제 스토어 검증은 카테고리·원산지·주소록·고시의 읽기 전용 조회로 제한하며 테스트 상품이나 이미지를 등록하지 않는다.

2026-09-28 검증 결과: 백엔드 전체 648개 테스트, 프런트 빌드·린트, 모의 API 브라우저 12개 시나리오가 통과했다. 연결된 본인 스토어의 실제 조회에서 카테고리 5,002개, 원산지 코드 535개, 국내 출고지·반품지 각 1개를 확인했다. 티셔츠 최종 카테고리를 대분류로 변환한 고시 조회도 정상 응답했으며, 관련 원격 조회는 모두 HTTP 200이었다. 실제 상품 생성·이미지 업로드는 실행하지 않았다.
