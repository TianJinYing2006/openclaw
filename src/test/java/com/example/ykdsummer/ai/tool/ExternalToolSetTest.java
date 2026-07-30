package com.example.ykdsummer.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.tool.feishu.FeishuApprovalTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuBitableTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuCalendarTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuDocTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuDriveTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuMessageTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuUserTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ExternalToolSetTest {

    @Test
    void excludesFeishuToolsWhenTheirBeansAreDisabled() {
        BochaWebSearchTools bocha = mock(BochaWebSearchTools.class);

        ExternalToolSet tools = new ExternalToolSet(bocha, absent(FeishuDocTools.class), absent(FeishuDriveTools.class),
                absent(FeishuMessageTools.class), absent(FeishuUserTools.class), absent(FeishuCalendarTools.class),
                absent(FeishuBitableTools.class), absent(FeishuApprovalTools.class));

        assertThat(tools.toolBeans()).containsExactly(bocha);
    }

    @Test
    void includesFeishuToolsAgainWhenTheirBeansAreEnabled() {
        BochaWebSearchTools bocha = mock(BochaWebSearchTools.class);
        FeishuDocTools document = mock(FeishuDocTools.class);

        ExternalToolSet tools = new ExternalToolSet(bocha, present(FeishuDocTools.class, document), absent(FeishuDriveTools.class),
                absent(FeishuMessageTools.class), absent(FeishuUserTools.class), absent(FeishuCalendarTools.class),
                absent(FeishuBitableTools.class), absent(FeishuApprovalTools.class));

        assertThat(tools.toolBeans()).containsExactly(bocha, document);
    }

    private static <T> ObjectProvider<T> absent(Class<T> type) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static <T> ObjectProvider<T> present(Class<T> type, T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
