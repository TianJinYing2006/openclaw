package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.fashion.config.FashionSemanticProperties;
import com.example.ykdsummer.fashion.domain.FashionSemanticIndexJob;
import com.example.ykdsummer.fashion.domain.WardrobeItem;
import com.example.ykdsummer.fashion.persistence.FashionCoreRepository;
import com.example.ykdsummer.fashion.persistence.FashionSemanticIndexJobRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.fashion.semantic", name = "enabled", havingValue = "true")
public class FashionSemanticIndexService {
    private static final Logger log = LoggerFactory.getLogger(FashionSemanticIndexService.class);

    private final FashionCoreRepository wardrobe;
    private final FashionSemanticIndexJobRepository jobs;
    private final FashionWardrobeVectorDocumentFactory documents;
    private final VectorStore vectorStore;
    private final FashionSemanticProperties properties;

    public FashionSemanticIndexService(
            FashionCoreRepository wardrobe,
            FashionSemanticIndexJobRepository jobs,
            FashionWardrobeVectorDocumentFactory documents,
            @Qualifier("fashionVectorStore") VectorStore vectorStore,
            FashionSemanticProperties properties
    ) {
        this.wardrobe = wardrobe;
        this.jobs = jobs;
        this.documents = documents;
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public List<FashionSemanticIndexJob> claimWork() {
        return jobs.claimPending(properties.getBatchSize(), properties.getProcessingLease(), properties.getMaxAttempts());
    }

    public void execute(FashionSemanticIndexJob job) {
        try {
            Optional<WardrobeItem> item = wardrobe.findWardrobeItemById(job.wardrobeItemId());
            if ("DELETE".equals(job.operation()) || item.isEmpty() || !"ACTIVE".equals(item.get().itemStatus())) {
                vectorStore.delete(List.of(documents.documentId(job.wardrobeItemId())));
                jobs.complete(job.id(), "");
                log.info("Fashion semantic index deleted wardrobeItem={}", job.wardrobeItemId());
                return;
            }
            Document document = documents.document(item.get());
            vectorStore.add(List.of(document));
            jobs.complete(job.id(), documents.contentHash(item.get()));
            log.info("Fashion semantic index updated wardrobeItem={}, model={}",
                    job.wardrobeItemId(), properties.getEmbeddingModel());
        } catch (RuntimeException failure) {
            Duration delay = Duration.ofSeconds(Math.min(60L, 1L << Math.min(job.attempts(), 6)));
            jobs.retry(job.id(), rootMessage(failure), properties.getMaxAttempts(), delay);
            log.warn("Fashion semantic index failed wardrobeItem={}, attempt={}: {}",
                    job.wardrobeItemId(), job.attempts(), rootMessage(failure));
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
