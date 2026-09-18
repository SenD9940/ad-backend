package com.orinan.api.config.aws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * application-local.yml 의 cloud.aws.* / app.aws.s3.* 설정을 사용합니다.
 */
@Configuration
@EnableConfigurationProperties(AwsS3Properties.class)
public class AwsS3Config {

    @Bean
    public S3Presigner s3Presigner(
            @Value("${cloud.aws.region.static}") String region,
            @Value("${cloud.aws.credentials.access-key}") String accessKey,
            @Value("${cloud.aws.credentials.secret-key}") String secretKey
    ) {
        return S3Presigner.builder()
                .region(Region.of(region.strip()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey.strip(), secretKey.strip())))
                .build();
    }


    @Bean
    public S3Client s3Client(
            @Value("${cloud.aws.region.static}") String region,
            @Value("${cloud.aws.credentials.access-key}") String accessKey,
            @Value("${cloud.aws.credentials.secret-key}") String secretKey
    ) {
        if (region == null || region.isBlank()) {
            throw new IllegalStateException(
                    "cloud.aws.region.static(AWS_REGION) 이 비어 있습니다. "
                            + "서버 .env 또는 GitHub Secrets에 AWS_REGION=ap-northeast-2 등을 설정하세요."
            );
        }
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "AWS IAM 자격증명이 비어 있습니다. "
                            + "AWS_IAM_ACCESS_KEY / AWS_IAM_SECRET_KEY 를 확인하세요."
            );
        }
        return S3Client.builder()
                .region(Region.of(region.strip()))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey.strip(), secretKey.strip())
                        )
                )
                .build();
    }
}
