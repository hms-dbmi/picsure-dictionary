package edu.harvard.dbmi.avillach.dictionary.util;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Value("${cache.concepts.maximum-size:500}")
    private int conceptsMaxSize;

    @Value("${cache.concepts.duration-minutes:30}")
    private int conceptsDurationMinutes;

    @Value("${cache.concepts-count.maximum-size:2000}")
    private int conceptsCountMaxSize;

    @Value("${cache.concepts-count.duration-minutes:30}")
    private int conceptsCountDurationMinutes;

    @Value("${cache.facets.maximum-size:500}")
    private int facetsMaxSize;

    @Value("${cache.facets.duration-minutes:30}")
    private int facetsDurationMinutes;

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(
            List.of(
                buildCache("concepts", conceptsMaxSize, conceptsDurationMinutes),
                buildCache("concepts_count", conceptsCountMaxSize, conceptsCountDurationMinutes),
                buildCache("facets", facetsMaxSize, facetsDurationMinutes)
            )
        );
        return manager;
    }

    private CaffeineCache buildCache(String name, int maxSize, int durationMinutes) {
        return new CaffeineCache(
            name, Caffeine.newBuilder().maximumSize(maxSize).expireAfterWrite(durationMinutes, TimeUnit.MINUTES).recordStats().build()
        );
    }
}
