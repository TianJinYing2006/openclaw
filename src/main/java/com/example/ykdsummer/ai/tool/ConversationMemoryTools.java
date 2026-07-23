package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.bot.file.FileSessionService;
import com.example.ykdsummer.bot.file.LocalDocumentAssetStore;
import java.util.function.Supplier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 当前微信会话的“忘记”工具。
 *
 * <p>它不是全局清理按钮：工具从 {@link ToolArtifactCollector} 获取当前 iLink 用户 ID，
 * 因此一次调用只影响发出请求的那一位用户。已保存的图片/文档版本和音色偏好不是临时聊天记忆，
 * 不在本工具的删除范围内。固定“清空”命令也采用同样的清理范围，以便 AI 不可用时仍有安全兜底。</p>
 */
@Component
public class ConversationMemoryTools {

    /** 延迟取聊天服务，避免 AiChatService -> 网关 -> Tool -> AiChatService 的启动循环。 */
    private final Supplier<AiChatService> chatService;
    private final FileSessionService fileSessions;
    private final LocalImageAssetStore imageStore;
    private final LocalDocumentAssetStore documentStore;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;

    public ConversationMemoryTools(
            AiChatService chatService,
            FileSessionService fileSessions,
            LocalImageAssetStore imageStore,
            LocalDocumentAssetStore documentStore,
            ToolArtifactCollector artifacts
    ) {
        this(() -> chatService, fileSessions, imageStore, documentStore, artifacts, AiTraceLogger.disabled());
    }

    @Autowired
    public ConversationMemoryTools(
            ObjectProvider<AiChatService> chatService,
            FileSessionService fileSessions,
            LocalImageAssetStore imageStore,
            LocalDocumentAssetStore documentStore,
            ToolArtifactCollector artifacts,
            AiTraceLogger trace
    ) {
        this(chatService::getObject, fileSessions, imageStore, documentStore, artifacts, trace);
    }

    private ConversationMemoryTools(
            Supplier<AiChatService> chatService,
            FileSessionService fileSessions,
            LocalImageAssetStore imageStore,
            LocalDocumentAssetStore documentStore,
            ToolArtifactCollector artifacts,
            AiTraceLogger trace
    ) {
        this.chatService = chatService;
        this.fileSessions = fileSessions;
        this.imageStore = imageStore;
        this.documentStore = documentStore;
        this.artifacts = artifacts;
        this.trace = trace;
    }

    @Tool(name = "clear_current_memory", description = "仅当用户明确要求清除、忘记、重置当前对话记忆，"
            + "或明确要求删除本次会话的临时缓存时调用。它只清除当前微信用户的短期聊天历史、"
            + "待处理文件和当前图片/文档会话指针；不会删除已保存的图片或文档版本，也不会重置音色。"
            + "用户只是换话题、让你总结、或一般性地说“不要记住”但未要求立即清空时不要调用。")
    public String clearCurrentMemory() {
        String userId = artifacts.userId();
        if ("unknown".equals(userId)) {
            return "当前会话身份不可用，未执行清理。";
        }
        trace.toolCall("clear_current_memory", "current user temporary conversation state");
        try {
            chatService.get().clear(userId);
            fileSessions.clear(userId);
            boolean imagePointerRemoved = imageStore.clearCurrent(userId);
            boolean documentPointerRemoved = documentStore.clearCurrent(userId);
            String result = "已清除当前对话记忆和临时会话缓存"
                    + "（当前图片指针：" + (imagePointerRemoved ? "已清除" : "无")
                    + "，当前文档指针：" + (documentPointerRemoved ? "已清除" : "无")
                    + "）。已保存的图片/文档版本和当前音色没有删除。";
            trace.toolResult("clear_current_memory", "completed");
            return result;
        } catch (RuntimeException exception) {
            trace.toolResult("clear_current_memory", "failed=" + exception.getClass().getSimpleName());
            return "清除当前记忆时出现问题，未确认清理完成。请稍后再试。";
        }
    }
}
