package com.example.ykdsummer.fashion.wardrobe.runtime;

import com.example.ykdsummer.fashion.wardrobe.application.FashionReferenceImportService;
import com.example.ykdsummer.fashion.wardrobe.config.FashionReferenceProperties;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.fashion.reference", name = "import-enabled", havingValue = "true")
public class FashionReferenceImportRunner {
    private static final Logger log = LoggerFactory.getLogger(FashionReferenceImportRunner.class);
    private final FashionReferenceImportService importer;
    private final FashionReferenceProperties properties;

    public FashionReferenceImportRunner(FashionReferenceImportService importer, FashionReferenceProperties properties) {
        this.importer = importer;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void importAfterStartup() {
        var report = importer.importFile(Path.of(properties.getAnnotationFile()), Path.of(properties.getImageDirectory()),
                properties.getImportLimit(), properties.isPublishImported(), properties.importOptions());
        log.info("Fashion public reference import completed: imported={}, skipped={}, failures={}",
                report.imported(), report.skipped(), report.failures().size());
        report.failures().stream().limit(10).forEach(value -> log.warn("Fashion reference import skipped: {}", value));
    }
}
