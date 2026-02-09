package edu.harvard.dbmi.avillach.dictionary.search;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "search.backend", havingValue = "meilisearch")
public class MeilisearchConfig {

    @Bean
    public Client meilisearchClient(@Value("${meilisearch.url}") String url, @Value("${meilisearch.api-key}") String apiKey) {
        return new Client(new Config(url, apiKey));
    }
}
