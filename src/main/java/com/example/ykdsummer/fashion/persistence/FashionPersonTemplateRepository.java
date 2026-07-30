package com.example.ykdsummer.fashion.persistence;

import com.example.ykdsummer.fashion.domain.FashionImageAsset;
import com.example.ykdsummer.fashion.domain.FashionPersonTemplate;
import com.example.ykdsummer.fashion.domain.PersonTemplateAssessment;
import java.util.List;
import java.util.Optional;

/** Persistent, per-user ownership boundary for virtual try-on source templates. */
public interface FashionPersonTemplateRepository {
    FashionPersonTemplate saveActive(String externalUserId, FashionImageAsset source, String displayName,
                                     PersonTemplateAssessment assessment);
    List<FashionPersonTemplate> list(String externalUserId, int limit);
    Optional<FashionPersonTemplate> active(String externalUserId);
    FashionPersonTemplate activate(String externalUserId, String templateId);
}
