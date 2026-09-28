package com.orinan.adminapi.config.aws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AdminAiS3Properties.class)
public class AdminAiS3Config {
    @Bean @Lazy
    public S3Client adminAiS3Client(@Value("${cloud.aws.region.static:ap-northeast-2}") String region,
            @Value("${cloud.aws.credentials.access-key:}") String access,
            @Value("${cloud.aws.credentials.secret-key:}") String secret) {
        return S3Client.builder().region(Region.of(region.strip())).credentialsProvider(credentials(access, secret))
                .overrideConfiguration(builder -> builder.apiCallTimeout(Duration.ofSeconds(20))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10))).build();
    }

    @Bean @Lazy
    public S3Presigner adminAiS3Presigner(@Value("${cloud.aws.region.static:ap-northeast-2}") String region,
            @Value("${cloud.aws.credentials.access-key:}") String access,
            @Value("${cloud.aws.credentials.secret-key:}") String secret) {
        return S3Presigner.builder().region(Region.of(region.strip())).credentialsProvider(credentials(access, secret)).build();
    }

    private StaticCredentialsProvider credentials(String access, String secret) {
        if (access == null || access.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalStateException("AI 템플릿 이미지 저장소 설정이 필요합니다.");
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(access.strip(), secret.strip()));
    }
}
