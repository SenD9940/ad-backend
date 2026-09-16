package com.orinan.db.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoConfiguration {

    @Bean
    public AesGcmStringEncryptor aesGcmStringEncryptor(
            CryptoProperties cryptoProperties
    ) {
        return new AesGcmStringEncryptor(cryptoProperties);
    }

    @Bean
    public DataCryptConverter dataCryptConverter(
            AesGcmStringEncryptor encryptor
    ) {
        return new DataCryptConverter(encryptor);
    }
}