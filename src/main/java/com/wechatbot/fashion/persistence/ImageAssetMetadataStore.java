package com.wechatbot.fashion.persistence;

import com.wechatbot.fashion.ai.service.LocalImageAssetStore.StoredImage;

public interface ImageAssetMetadataStore {
    void record(String userId, StoredImage image, String storageProvider);

    static ImageAssetMetadataStore disabled() { return Disabled.INSTANCE; }

    enum Disabled implements ImageAssetMetadataStore {
        INSTANCE;
        @Override public void record(String userId, StoredImage image, String storageProvider) { }
    }
}
