package com.example.ykdsummer.persistence;

import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;

public interface ImageAssetMetadataStore {
    void record(String userId, StoredImage image, String storageProvider);

    static ImageAssetMetadataStore disabled() { return Disabled.INSTANCE; }

    enum Disabled implements ImageAssetMetadataStore {
        INSTANCE;
        @Override public void record(String userId, StoredImage image, String storageProvider) { }
    }
}
