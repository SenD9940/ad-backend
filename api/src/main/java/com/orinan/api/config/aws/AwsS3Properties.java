package com.orinan.api.config.aws;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.aws.s3")
public class AwsS3Properties {

    /** 버킷 이름 (application-local: app.aws.s3.bucket) */
    private String bucket;

    /** 객체 키 prefix (기본: auto-threads) */
    private String keyPrefix = "auto-threads";
}
