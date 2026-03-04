package edu.harvard.dbmi.avillach.dictionary.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleCacheManager;

class CacheConfigTest {

    private CacheManager manager;

    @BeforeEach
    void setUp() {
        CacheConfig config = new CacheConfig();
        manager = config.cacheManager();
        ((SimpleCacheManager) manager).afterPropertiesSet();
    }

    @Test
    void shouldCreateAllCaches() {
        Assertions.assertNotNull(manager.getCache("concepts"));
        Assertions.assertNotNull(manager.getCache("concepts_count"));
        Assertions.assertNotNull(manager.getCache("facets"));
    }

    @Test
    void shouldReturnNullForUnknownCache() {
        Assertions.assertNull(manager.getCache("nonexistent"));
    }
}
