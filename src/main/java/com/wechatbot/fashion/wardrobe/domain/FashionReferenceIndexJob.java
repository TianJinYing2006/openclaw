package com.wechatbot.fashion.wardrobe.domain;

public record FashionReferenceIndexJob(long id, long referenceLookId, String operation, int attempts) { }
