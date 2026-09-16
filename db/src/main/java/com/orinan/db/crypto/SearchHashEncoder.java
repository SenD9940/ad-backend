package com.orinan.db.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

@Component
public class SearchHashEncoder {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_LENGTH_BYTES = 32;

    private final SecretKeySpec secretKey;

    public SearchHashEncoder(
            @Value("${aes.key.search-hmac-key}") String base64Key
    ) {
        this.secretKey = createSecretKey(base64Key);
    }

    public String encode(String value) {
        Objects.requireNonNull(value, "검색 해시 대상 값은 null일 수 없습니다.");

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secretKey);

            byte[] hash = mac.doFinal(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            // HMAC-SHA256은 hex 문자열로 변환하면 64자
            return HexFormat.of().formatHex(hash);

        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "검색용 HMAC 해시 생성에 실패했습니다.",
                    exception
            );
        }
    }

    private SecretKeySpec createSecretKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "orinan.crypto.search-hmac-key 설정이 필요합니다."
            );
        }

        try {
            byte[] decodedKey = Base64.getDecoder().decode(base64Key);

            if (decodedKey.length < MIN_KEY_LENGTH_BYTES) {
                throw new IllegalStateException(
                        "검색용 HMAC 키는 최소 32바이트 이상이어야 합니다."
                );
            }

            return new SecretKeySpec(decodedKey, ALGORITHM);

        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "검색용 HMAC 키가 올바른 Base64 형식이 아닙니다.",
                    exception
            );
        }
    }
}