package com.orinan.adminapi.config.jpa;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EntityScan(basePackages = "com.orinan.db")
@EnableJpaRepositories(basePackages = "com.orinan.db")
public class JpaConfig {}
