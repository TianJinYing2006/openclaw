package com.example.ykdsummer.fashion.tool;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Single registration point so a Fashion tool cannot exist in Spring but be absent from the actual Agent. */
@Component
public class FashionAgentToolSet {
    private final Object[] tools;

    public FashionAgentToolSet(
            ObjectProvider<FashionTools> core,
            ObjectProvider<FashionWardrobeIntakeTools> intake,
            ObjectProvider<FashionVisualPreviewTools> previews,
            ObjectProvider<FashionSemanticTools> semantic,
            ObjectProvider<FashionPersonTemplateTools> templates,
            ObjectProvider<FashionTryOnTools> tryOn,
            ObjectProvider<FashionReferenceTools> references,
            ObjectProvider<FashionOutfitRecommendationTools> recommendations
    ) {
        List<Object> values = new ArrayList<>();
        add(values, core); add(values, intake); add(values, previews); add(values, semantic);
        add(values, templates); add(values, tryOn); add(values, references); add(values, recommendations);
        this.tools = values.toArray();
    }

    public Object[] toolBeans() { return tools.clone(); }
    private static void add(List<Object> values, ObjectProvider<?> provider) {
        Object bean = provider.getIfAvailable(); if (bean != null) values.add(bean);
    }
}
