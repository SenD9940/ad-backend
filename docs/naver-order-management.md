# 스마트스토어 주문·배송·취소·반품·결제·정산

저장된 네이버 스마트스토어에서 상품주문을 조회하고 발주 확인, 발송, 구매자가 요청한 취소·반품 승인을 처리합니다. 상품·판매 성과 화면의 **주문 관리**에서 이용합니다. 기존 서비스 토큰·워크스페이스 멤버 권한·네이버 연결 정보를 사용하며 새로운 SQL이나 환경변수는 없습니다. 중복 처리 방지에는 기존 Redis를 사용합니다.

네이버 커머스API 애플리케이션에 주문·정산 API 그룹 사용 권한과 서버의 외부 IP 허용 설정이 필요합니다. 현재 사용하는 수동 연결·자기 스토어 테스트 연결·커머스솔루션 연결에 같은 기능을 적용합니다. 기술 지원 세션에는 주문·주소·정산·환불 경로를 추가로 허용하지 않습니다.

## 지원 범위

- **주문 조회**: 주문일·결제일·발송 처리일·클레임 요청일·완료일 중 기준을 선택해 한국 시간 하루씩 조회합니다. 상품주문번호로 직접 상세 조회도 가능합니다.
- **결제 정보**: 결제 수단, 결제일·기한, 상품 최초 결제액·잔여 결제액, 최초·잔여 수량을 표시합니다. 결제창 호출이나 별도 결제 승인·청구 기능은 아닙니다.
- **배송**: 발주 확인, 택배사·송장·발송 일시를 통한 발송 처리, 배송 상태·집화·배송 완료 시각과 오류 송장 여부를 표시합니다. 방문 수령·직접 전달·퀵서비스·배송 없음도 선택할 수 있습니다.
- **취소·반품**: 현재 구매자 요청의 클레임 수량·사유·상태를 확인하고 승인합니다. 반품은 실물 수령과 비용을 확인한 뒤 승인합니다. 보류 상태는 스마트스토어센터에서 처리하도록 안내합니다.
- **교환·과거 클레임**: 진행 상태와 완료 이력을 조회합니다. 교환 재배송, 새 취소/반품 요청 생성, 보류 해제·거절·비용 변경은 스마트스토어센터에서 처리합니다.
- **일별 정산**: 정산 예정일 기준 최대 28일을 페이지 단위로 조회합니다. 정산 API에는 채널 필터가 없어, 원격 판매자 조회에서 접근 가능한 채널이 정확히 하나인 경우에만 제공합니다.

실제 주문을 변경하는 버튼은 대상 주문과 처리 내용을 먼저 표시합니다. 실제 스마트스토어센터와 같은 판매자 작업이므로 발주·발송·승인은 사용자가 최종 확인한 시점에만 전송합니다. 기능 검증 중 실제 주문·환불을 변경하지 않았습니다.

## API

공통 경로: `/api/workspaces/{workspaceId}/naver/stores/{assetId}`. `assetId`는 저장된 채널의 내부 자산 ID이고 `productOrderId`는 네이버 상품주문번호입니다. JSON은 snake_case, 성공 응답은 `Api.body`입니다.

| 메서드·경로 | 기능 |
| --- | --- |
| `GET /order-options` | 조회 기준·상태·배송 방법·택배사·정산 지원 여부 |
| `GET /orders?date=2026-09-29&range_type=ORDERED_DATETIME&page=1&size=20` | 상품주문 목록. `status` 선택 가능 |
| `GET /orders/{productOrderId}` | 결제·배송지·배송·현재/과거 클레임·현재 가능한 처리 |
| `POST /orders/{productOrderId}/actions` | 검토한 주문 처리 |
| `GET /settlements?since=2026-09-01&until=2026-09-28&page=1&size=20` | 일별 정산 |

주문 목록은 네이버의 판매자 계정 페이지에서 선택한 `merchantChannelId`만 표시합니다. 다른 채널 주문이 포함된 페이지는 화면에 표시되는 항목이 적거나 없을 수 있습니다. `has_next=true`이면 다음 페이지를 조회할 수 있으며, 현재 페이지 합계를 전체 채널 성과라고 표시하지 않습니다.

상세 응답에는 `version`과 서버가 계산한 `actions`가 있습니다. 처리 요청의 `expected_version`은 검토한 상세 응답의 버전입니다. `request_id`는 요청마다 생성한 UUID이며 같은 요청을 다시 보내지 않습니다.

```json
{
  "action": "DISPATCH",
  "expected_version": "상세 응답의 64자리 version",
  "request_id": "5c5a9a77-2ad1-4d53-89dd-fcc13e717f50",
  "delivery_method": "DELIVERY",
  "delivery_company_code": "CJGLS",
  "tracking_number": "123456789012",
  "dispatch_date": "2026-09-29T10:00:00+09:00"
}
```

`action`은 `CONFIRM`, `DISPATCH`, `APPROVE_CANCEL`, `APPROVE_RETURN`입니다. 택배사·송장은 `DELIVERY`에만 보냅니다. 발송 일시는 결제 이후이면서 현재 시각 이전이어야 합니다. 반품 승인에는 `return_received: true`가 필요합니다. 발주 확인과 취소 승인에는 공통 세 필드만 보냅니다. 공급자 승인 API는 상품주문번호만 받으므로 임의 환불 금액이나 승인 수량을 전송하지 않습니다.

네이버가 해당 상품주문번호의 성공을 명시하면 `{ "product_order_id": "...", "action": "...", "status": "ACCEPTED", "notice": "..." }`를 반환합니다. 발주 확인은 다른 처리와 성공 응답 형태가 달라 별도로 검사합니다. 결과 확인 후 최신 주문 상세를 다시 조회합니다.

## 금액과 상태의 의미

상품 결제액은 `productOrder.initialPaymentAmount`와 `remainPaymentAmount`를 사용합니다. 초기 값이 제공되지 않는 구형 응답에만 `totalPaymentAmount`를 최초 금액으로 사용합니다. 현재 금액·수량이 누락되면 확인되지 않은 값으로 표시하며 0이나 최초 값으로 바꾸지 않습니다.

배송비는 상품 결제액에 포함하지 않습니다. 네이버의 주문서 전체 결제액에는 다른 판매자의 상품이 포함될 수 있으므로 `order.generalPaymentAmount` 등을 이 스토어의 결제 합계로 사용하지 않습니다. 최초와 잔여 금액의 차이를 실제 환불 입금액으로 표시하지 않습니다.

부분 취소 완료 후에도 상품주문이 `PAYED`이고 잔여 수량이 있으면 그 수량의 발주·발송을 지원합니다. 진행 중인 취소 요청에서 발송하면 네이버가 취소를 거부할 수 있으므로 앱에서는 차단합니다. 선물 수락 대기, 물류 직계약, 해석할 수 없는 클레임 상태도 발주·발송을 막고 센터 확인을 안내합니다.

반품 승인은 보류되지 않은 현재 요청에 한해 제공합니다. 네이버 정책상 같은 주문의 다른 반품 환불도 함께 처리될 수 있음을 승인 화면에서 알립니다. `HOLDBACK` 상태에는 환불금 차감·반품안심케어 등 비용 규칙이 있어 자동으로 보류를 해제하지 않습니다. 판매자 반품 처리 완료와 구매자의 카드·계좌 환불 완료는 서로 다르며 실제 입금 시점은 이 API로 확인할 수 없습니다.

정산 목록은 `settleExpectDate` 기준입니다. 정산 완료일이 없으면 완료로 단정하지 않습니다. 금액의 누락 값과 차감·복원에 따른 음수를 유지하고, 계좌번호·은행 정보는 응답에 포함하지 않습니다. 페이지 일부를 조회한 금액을 기간 전체 합계로 표시하지 않습니다.

## 권한·동시 처리·오류

조회와 변경 전에 현재 워크스페이스 멤버·저장 자산·토큰·실제 접근 채널을 확인합니다. 상세의 `merchantChannelId`와 요청한 상품주문번호를 검증하며, 다른 채널 주문은 수정하지 않습니다. 변경 직전 원격 상세를 다시 읽고 배송지·금액·클레임을 포함한 버전이 사용자가 검토한 내용과 일치하는지 확인합니다.

Redis에서 판매자 계정·상품주문 기준으로 처리를 직렬화하고 요청 UUID를 24시간 기억합니다. 결과가 불확실하면 동일 상품주문의 후속 처리를 10분간 보류하고 스마트스토어센터 확인을 안내합니다. 원격 서비스에는 조건부 변경 기능이 없어 외부 센터에서 동시에 변경하는 경우까지 원자적으로 잠글 수는 없습니다. 최종 승인 여부는 네이버가 판단합니다.

읽기 전용 상세 조회의 POST는 재조회할 수 있지만 발주·발송·승인 POST는 토큰 갱신, HTTP 인터셉터, TCP 재시도로 자동 재전송하지 않습니다. HTTP 200이라도 대상 상품주문의 성공·실패가 명확하지 않으면 결과 불명확으로 처리합니다. 타임아웃·네트워크·서버 오류 뒤에는 실패만으로 값이 유지되었다고 판단하지 말고 주문 상태를 먼저 확인합니다.

응답은 `Cache-Control: no-store`이며 수령인 정보는 상세 화면에 필요한 필드만 반환합니다. 주문 원문·구매자 정보·토큰은 로그나 DB에 저장하지 않고 Redis에는 해시 키와 요청 처리 표식만 남깁니다.

## 검증과 공식 자료

모의 HTTP, 인증 만료, 다른 채널 차단, 부분 클레임, 결제 누락 값, 수령인 정보 변경, 중복 요청, 불확실한 처리 결과, 단일 채널 정산, 양수·음수·누락 금액을 검증합니다. 프론트엔드는 모의 API로 화면과 확인 절차·오류·모바일·지원 모드 제한을 점검합니다.

- [조건형 주문 조회](https://apicenter.commerce.naver.com/docs/commerce-api/current/seller-get-product-orders-with-conditions-pay-order-seller), [상품주문 상세](https://apicenter.commerce.naver.com/docs/commerce-api/current/seller-get-product-orders-pay-order-seller)
- [발주 확인](https://apicenter.commerce.naver.com/docs/commerce-api/current/seller-confirm-placed-product-orders-pay-order-seller), [발송 처리](https://apicenter.commerce.naver.com/docs/commerce-api/current/seller-dispatch-product-orders-pay-order-seller)
- [취소 승인](https://apicenter.commerce.naver.com/docs/commerce-api/current/seller-approve-cancel-application-pay-order-seller), [반품 승인](https://apicenter.commerce.naver.com/docs/commerce-api/current/seller-approve-return-pay-order-seller)
- [일별 정산](https://apicenter.commerce.naver.com/docs/commerce-api/current/find-daily-settle-pay-settle)
- 공식 안내: [부분 취소 후 발송](https://github.com/commerce-api-naver/commerce-api/discussions/3023), [결제 금액 범위](https://github.com/commerce-api-naver/commerce-api/discussions/1058), [반품 보류와 비용](https://github.com/commerce-api-naver/commerce-api/discussions/398), [실제 환불 시점](https://github.com/commerce-api-naver/commerce-api/discussions/3498)
