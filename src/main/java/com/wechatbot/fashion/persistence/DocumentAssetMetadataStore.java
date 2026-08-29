package com.wechatbot.fashion.persistence;

import com.wechatbot.fashion.bot.file.LocalDocumentAssetStore.StoredDocument;

/** Durable document asset index. Object bytes stay in OSS or the local development store. */
public interface DocumentAssetMetadataStore {
    void record(String userId, StoredDocument document, String storageProvider);

    static DocumentAssetMetadataStore disabled() { return Disabled.INSTANCE; }

    enum Disabled implements DocumentAssetMetadataStore {
        INSTANCE;
        @Override public void record(String userId, StoredDocument document, String storageProvider) { }
    }
}
