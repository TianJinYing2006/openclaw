package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.fashion.config.FashionSemanticProperties;
import com.example.ykdsummer.fashion.domain.FashionReferenceIndexJob;
import com.example.ykdsummer.fashion.domain.FashionReferenceLook;
import com.example.ykdsummer.fashion.persistence.FashionReferenceRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = {"app.fashion.semantic.enabled", "app.fashion.reference.enabled"}, havingValue = "true")
public class FashionReferenceIndexService {
    private static final Logger log = LoggerFactory.getLogger(FashionReferenceIndexService.class);
    private final FashionReferenceRepository repository;
    private final FashionReferenceVectorDocumentFactory documents;
    private final VectorStore vectorStore;
    private final FashionSemanticProperties properties;

    public FashionReferenceIndexService(FashionReferenceRepository repository,
            FashionReferenceVectorDocumentFactory documents,
            @Qualifier("fashionVectorStore") VectorStore vectorStore, FashionSemanticProperties properties) {
        this.repository = repository;
        this.documents = documents;
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public List<FashionReferenceIndexJob> claimWork() {
        return repository.claimPendingIndexJobs(properties.getBatchSize(), properties.getProcessingLease(),
                properties.getMaxAttempts());
    }

    public void execute(FashionReferenceIndexJob job) {
        try {
            Optional<FashionReferenceLook> look = repository.findById(job.referenceLookId());
            if ("DELETE".equals(job.operation()) || look.isEmpty() || !"ACTIVE".equals(look.get().status())) {
                vectorStore.delete(List.of(documents.documentId(job.referenceLookId())));
                repository.completeIndexJob(job.id(), "");
                return;
            }
            vectorStore.add(List.of(documents.document(look.get())));
            repository.completeIndexJob(job.id(), documents.contentHash(look.get()));
            log.info("Fashion public reference indexed look={}", job.referenceLookId());
        } catch (RuntimeException failure) {
            Duration delay = Duration.ofSeconds(Math.min(60L, 1L << Math.min(job.attempts(), 6)));
            repository.retryIndexJob(job.id(), rootMessage(failure), properties.getMaxAttempts(), delay);
            log.warn("Fashion public reference indexing failed look={}: {}", job.referenceLookId(), rootMessage(failure));
        }
    }
    private static String rootMessage(Throwable failure) {
        Throwable current = failure; while (current.getCause() != null) current = current.getCause();
        return current.getClass().getSimpleName() + ": " + String.valueOf(current.getMessage());
    }
}
