package com.orinan.api.domain.support.payment;

import com.orinan.api.common.code.CodeIfs;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SupportPaymentErrorCode implements CodeIfs {
    INVALID_REQUEST(400, 8150, "결제 요청 값을 확인해 주세요."),
    ACCESS_DENIED(403, 8151, "워크스페이스 소유자만 기술 지원 결제를 진행할 수 있습니다."),
    NOT_FOUND(404, 8152, "기술 지원 결제 주문을 찾을 수 없습니다."),
    CONFLICT(409, 8153, "기술 지원 요청 또는 결제 상태가 변경되었습니다. 다시 확인해 주세요."),
    PAYMENT_PENDING(409, 8154, "처리 중인 결제 주문이 있습니다. 기존 주문의 결제 결과를 먼저 확인해 주세요."),
    UNAVAILABLE(503, 8155, "결제 서비스를 사용할 수 없습니다. 잠시 후 다시 확인해 주세요."),
    RECONCILE_REQUIRED(503, 8156, "결제 결과를 확인하지 못했습니다. 기존 주문의 결제 내역을 다시 조회해 주세요.");

    private final Integer httpStatusCode;
    private final Integer code;
    private final String description;
}
