package com.wechatbot.fashion.wardrobe.domain;

/** Durable unit of work for synchronizing one wardrobe item to the vector store. */
public record FashionSemanticIndexJob(
        long id,
        long wardrobeItemId,
        String operation,
        int attempts
) { }
