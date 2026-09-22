package com.orinan.api.domain.platformconnection.controller.model;

import com.orinan.db.naverconnection.enums.NaverTokenType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
public class NaverConnectRequest {

    @NotBlank
    @Size(max = 255)
    private String clientId;

    @NotBlank
    // Validate the secret format in the client: binding errors can log rejected values.
    @ToString.Exclude
    private String clientSecret;

    @NotNull
    private NaverTokenType tokenType = NaverTokenType.SELF;

    @Size(max = 255)
    private String accountId;
}
