package com.wechatbot.fashion.wardrobe.application;

import com.wechatbot.fashion.wardrobe.domain.FashionProduct;
import com.wechatbot.fashion.wardrobe.domain.FashionProductDraft;
import com.wechatbot.fashion.wardrobe.domain.FashionProductSearch;
import com.wechatbot.fashion.wardrobe.persistence.FashionCatalogRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Application boundary for the platform-owned catalog and future supplier importers. */
@Service
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class FashionCatalogService {
    private final FashionCatalogRepository repository;

    public FashionCatalogService(FashionCatalogRepository repository) { this.repository = repository; }

    public FashionProduct saveProduct(FashionProductDraft draft) { return repository.upsertProduct(draft); }
    public List<FashionProduct> catalogProducts(int limit) { return repository.listCatalog(limit); }
    public Optional<FashionProduct> productByCode(String productCode) { return repository.findByProductCode(productCode); }
    public List<FashionProduct> searchActiveProducts(FashionProductSearch search) { return repository.searchActive(search); }
    public boolean updateAvailability(long productId, String availabilityStatus) {
        return repository.updateAvailability(productId, availabilityStatus);
    }
}
