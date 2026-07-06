package com.demo.payment.config;

// No changes from the Java 11 original.
// Caffeine 3.x (resolved by Spring Boot 3 BOM) maintains API compatibility
// with Caffeine 2.x for everything used here.
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("transactions");
        manager.setCaffeine(
            Caffeine.newBuilder()
                    .maximumSize(1000)
                    .expireAfterWrite(5, TimeUnit.MINUTES)
                    .recordStats()
        );
        return manager;
    }
}
