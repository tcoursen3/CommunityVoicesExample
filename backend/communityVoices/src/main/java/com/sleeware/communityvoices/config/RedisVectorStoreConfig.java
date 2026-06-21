package com.sleeware.communityvoices.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationConvention;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.RedisClient;

@Configuration
@ConditionalOnExpression("!'${spring.autoconfigure.exclude:}'.contains('RedisVectorStoreAutoConfiguration')")
class RedisVectorStoreConfig {

    @Bean
    RedisVectorStore vectorStore(
            EmbeddingModel embeddingModel,
            RedisVectorStoreProperties properties,
            JedisConnectionFactory jedisConnectionFactory,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<VectorStoreObservationConvention> convention,
            BatchingStrategy batchingStrategy) {

        RedisVectorStore.Builder builder = RedisVectorStore.builder(jedisClient(jedisConnectionFactory), embeddingModel)
                .initializeSchema(properties.isInitializeSchema())
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .customObservationConvention(convention.getIfAvailable())
                .batchingStrategy(batchingStrategy)
                .indexName(properties.getIndexName())
                .prefix(properties.getPrefix())
                .hnswM(properties.getHnsw().getM())
                .hnswEfConstruction(properties.getHnsw().getEfConstruction())
                .hnswEfRuntime(properties.getHnsw().getEfRuntime())
                .metadataFields(
                        RedisVectorStore.MetadataField.tag("source"),
                        RedisVectorStore.MetadataField.tag("subreddit"),
                        RedisVectorStore.MetadataField.tag("redditId"),
                        RedisVectorStore.MetadataField.text("title"),
                        RedisVectorStore.MetadataField.tag("author"),
                        RedisVectorStore.MetadataField.tag("createdAt"),
                        RedisVectorStore.MetadataField.numeric("ups"),
                        RedisVectorStore.MetadataField.numeric("downs"),
                        RedisVectorStore.MetadataField.numeric("comments"),
                        RedisVectorStore.MetadataField.numeric("chunkIndex"),
                        RedisVectorStore.MetadataField.numeric("chunkCount"));

        return builder.build();
    }

    private RedisClient jedisClient(JedisConnectionFactory jedisConnectionFactory) {
        JedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .ssl(jedisConnectionFactory.isUseSsl())
                .clientName(jedisConnectionFactory.getClientName())
                .timeoutMillis(jedisConnectionFactory.getTimeout())
                .password(jedisConnectionFactory.getPassword())
                .build();

        return RedisClient.builder()
                .hostAndPort(jedisConnectionFactory.getHostName(), jedisConnectionFactory.getPort())
                .clientConfig(clientConfig)
                .build();
    }
}
