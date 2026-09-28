package com.orinan.api.domain.support.payment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The secret key is server-only; never serialize this configuration bean. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.support.toss")
public class TossPaymentProperties {
    private String clientKey = "";
    private String secretKey = "";

    public boolean isConfigured() {
        return clientKey != null && !clientKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }
}
