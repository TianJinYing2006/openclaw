package com.wechatbot.fashion.wardrobe.persistence;

import com.wechatbot.fashion.wardrobe.domain.FashionImageAsset;
import com.wechatbot.fashion.wardrobe.domain.FashionPersonTemplate;
import com.wechatbot.fashion.wardrobe.domain.PersonTemplateAssessment;
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
