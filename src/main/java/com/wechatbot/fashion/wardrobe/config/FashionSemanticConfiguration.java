package com.wechatbot.fashion.wardrobe.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Fashion uses Bailian embeddings independently from the existing Kitty chat provider. */
@Configuration
@ConditionalOnProperty(prefix = "app.fashion.semantic", name = "enabled", havingValue = "true")
public class FashionSemanticConfiguration {

    @Bean("fashionEmbeddingModel")
    EmbeddingModel fashionEmbeddingModel(FashionSemanticProperties properties) {
        if (!properties.hasEmbeddingCredentials()) {
            throw new IllegalStateException("Fashion semantic retrieval requires a Bailian embedding API key");
        }
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(properties.getEmbeddingBaseUrl())
                .apiKey(properties.getEmbeddingApiKey())
                .embeddingsPath("/embeddings")
                .build();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(properties.getEmbeddingModel())
                .dimensions(properties.getEmbeddingDimensions())
                .encodingFormat("float")
                .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.NONE, options);
    }

    @Bean(destroyMethod = "close")
    QdrantClient fashionQdrantClient(FashionSemanticProperties properties) {
        // checkCompatibility=false: skip client/server version mismatch check.
        // Spring AI 1.1.8 pins Qdrant client 1.13.0 (API-compatible) while the
        // Docker server runs 1.18.3; gRPC protocol remains backward compatible.
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(
                properties.getQdrantHost(), properties.getQdrantPort(),
                properties.isQdrantTls(), false);
        if (!properties.getQdrantApiKey().isBlank()) {
            builder.withApiKey(properties.getQdrantApiKey());
        }
        return new QdrantClient(builder.build());
    }

    @Bean("fashionVectorStore")
    VectorStore fashionVectorStore(
            QdrantClient fashionQdrantClient,
            @Qualifier("fashionEmbeddingModel") EmbeddingModel embeddingModel,
            FashionSemanticProperties properties
    ) {
        return QdrantVectorStore.builder(fashionQdrantClient, embeddingModel)
                .collectionName(properties.getCollectionName())
                .initializeSchema(true)
                .build();
    }

    @Bean(name = "fashionSemanticExecutor", destroyMethod = "shutdown")
    ThreadPoolTaskExecutor fashionSemanticExecutor(FashionSemanticProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkerThreads());
        executor.setMaxPoolSize(properties.getWorkerThreads());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("fashion-semantic-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
