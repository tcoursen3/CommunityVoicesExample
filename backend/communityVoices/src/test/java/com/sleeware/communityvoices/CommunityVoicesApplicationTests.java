package com.sleeware.communityvoices;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreAutoConfiguration",
        "community-voices.jobs.scrape-community.enabled=false"
})
class CommunityVoicesApplicationTests {

    @Test
    void contextLoads() {
    }

    @TestConfiguration
    static class VectorStoreTestConfiguration {

        @Bean
        VectorStore vectorStore() {
            return new VectorStore() {
                @Override
                public void add(List<Document> documents) {
                }

                @Override
                public void delete(List<String> idList) {
                }

                @Override
                public void delete(Filter.Expression filterExpression) {
                }

                @Override
                public List<Document> similaritySearch(SearchRequest request) {
                    return List.of();
                }
            };
        }

    }

}
