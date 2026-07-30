package com.example.ykdsummer.fashion.identity;

/** Canonical owner for Fashion data inside one managed bot instance. */
public record FashionUserScope(long appUserId, String externalUserId, String instanceId, Long platformUserId) { }
