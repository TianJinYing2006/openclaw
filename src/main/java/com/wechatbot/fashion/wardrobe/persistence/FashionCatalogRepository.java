package com.wechatbot.fashion.wardrobe.persistence;

import com.wechatbot.fashion.wardrobe.domain.FashionProduct;
import com.wechatbot.fashion.wardrobe.domain.FashionProductDraft;
import com.wechatbot.fashion.wardrobe.domain.FashionProductSearch;
import java.util.List;
import java.util.Optional;

/** Persistence port for platform-owned fashion products. User wardrobe data stays in FashionCoreRepository. */
public interface FashionCatalogRepository {
    FashionProduct upsertProduct(FashionProductDraft draft);
    List<FashionProduct> listCatalog(int limit);
    Optional<FashionProduct> findByProductCode(String productCode);
    List<FashionProduct> searchActive(FashionProductSearch search);
    boolean updateAvailability(long productId, String availabilityStatus);
}
