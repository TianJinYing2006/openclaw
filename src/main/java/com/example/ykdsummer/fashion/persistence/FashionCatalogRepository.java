package com.example.ykdsummer.fashion.persistence;

import com.example.ykdsummer.fashion.domain.FashionProduct;
import com.example.ykdsummer.fashion.domain.FashionProductDraft;
import com.example.ykdsummer.fashion.domain.FashionProductSearch;
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
