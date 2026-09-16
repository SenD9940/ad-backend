package com.orinan.db.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aes.key")
public record CryptoProperties(
        String personalDataKey
) {
    
}
