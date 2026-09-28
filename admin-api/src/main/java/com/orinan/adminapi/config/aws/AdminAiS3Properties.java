package com.orinan.adminapi.config.aws;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.aws.s3")
public class AdminAiS3Properties {
    private String bucket;
    private String keyPrefix = "auto-threads";
}
