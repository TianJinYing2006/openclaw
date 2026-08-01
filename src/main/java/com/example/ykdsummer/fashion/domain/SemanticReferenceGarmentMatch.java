package com.example.ykdsummer.fashion.domain;

/** A single public garment match with its parent Look retained as pairing evidence. */
public record SemanticReferenceGarmentMatch(
        FashionReferenceLook look,
        FashionReferenceGarment garment,
        double score
) { }
