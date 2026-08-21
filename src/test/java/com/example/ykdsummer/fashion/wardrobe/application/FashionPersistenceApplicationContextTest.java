package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.ai.fashion.look.agent.FashionAgentWorkflowContextProvider;
import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.ai.orchestration.ToolRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

@EnabledIfEnvironmentVariable(named = "PERSISTENCE_INTEGRATION", matches = "true")
@SpringBootTest(
        properties = {
                "ilink.enabled=false",
                "app.persistence.enabled=true",
                "app.persistence.redis.enabled=false",
                "app.admin.enabled=false"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class FashionPersistenceApplicationContextTest {

    @Autowired
    private FashionAgentWorkflowContextProvider workflowContext;

    @Autowired
    private AiChatService chatService;

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void injectsTheDurableFashionWorkflowContextIntoTheChatService() {
        assertThat(ReflectionTestUtils.getField(chatService, "fashionWorkflowContext"))
                .isSameAs(workflowContext);
    }

    @Test
    void exposesTheCompleteFashionQueryToolChainWithoutDuplicateNames() {
        List<String> names = toolRegistry.allToolMeta().stream().map(ToolRegistry.ToolMeta::name).toList();

        assertThat(names).contains("search_wardrobe", "search_wardrobe_semantic", "show_wardrobe_items",
                "search_fashion_references", "recommend_outfits_from_wardrobe", "fashion_consultant");
        assertThat(names).hasSize(36);
        assertThat(names).doesNotHaveDuplicates();
    }
}
