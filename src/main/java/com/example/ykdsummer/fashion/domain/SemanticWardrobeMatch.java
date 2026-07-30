package com.example.ykdsummer.fashion.domain;

/** A MySQL-backed wardrobe item in vector similarity order. */
public record SemanticWardrobeMatch(WardrobeItem item, double score) { }
