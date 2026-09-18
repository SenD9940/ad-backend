package com.orinan.api.config.jpa;

import com.orinan.db.crypto.CryptoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EntityScan(basePackages = "com.orinan.db")
@EnableJpaRepositories(basePackages = "com.orinan.db")
@Import(CryptoConfiguration.class)
public class JpaConfig {
}
