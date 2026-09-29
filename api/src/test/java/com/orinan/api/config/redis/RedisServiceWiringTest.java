package com.orinan.api.config.redis;

import com.orinan.api.domain.imweb.service.ImwebOAuthStateService;
import com.orinan.api.domain.imweb.service.ImwebRefreshLease;
import com.orinan.api.domain.navercommerce.service.NaverOrderSessionService;
import com.orinan.api.domain.navercommerce.service.NaverOrderWriteGuard;
import com.orinan.api.domain.token.business.TokenBusiness;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisServiceWiringTest {

    @ParameterizedTest
    @ValueSource(classes = {
            ImwebOAuthStateService.class,
            ImwebRefreshLease.class,
            NaverOrderSessionService.class,
            NaverOrderWriteGuard.class
    })
    void usesApplicationTemplateWhenBootAlsoProvidesStringTemplate(Class<?> serviceType) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
                .withUserConfiguration(RedisConfig.class, serviceType)
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .withBean(TokenBusiness.class, () -> mock(TokenBusiness.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(RedisTemplate.class))
                            .containsOnlyKeys("redisTemplate", "stringRedisTemplate");
                    assertThat(context.getBean("stringRedisTemplate")).isInstanceOf(StringRedisTemplate.class);

                    var template = context.getBean("redisTemplate", RedisTemplate.class);
                    assertThat(ReflectionTestUtils.getField(context.getBean(serviceType), "redis"))
                            .isSameAs(template);
                    assertThat(template.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
                    assertThat(template.getValueSerializer()).isInstanceOf(StringRedisSerializer.class);
                });
    }
}
