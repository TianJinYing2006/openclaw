package com.example.ykdsummer.ai.tool;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class BochaWebSearchToolsTest {

    @Test
    void reportsMissingConfigurationWithoutCallingTheNetwork() {
        BochaWebSearchTools tools = new BochaWebSearchTools("", RestClient.create());

        assertThat(tools.searchWeb("今日科技新闻")).isEqualTo("博查搜索未配置 API Key");
    }

    @Test
    void rejectsBlankQueryBeforeCallingTheNetwork() {
        BochaWebSearchTools tools = new BochaWebSearchTools("configured", RestClient.create());

        assertThat(tools.searchWeb("  ")).isEqualTo("搜索关键词不能为空");
    }
}
