package com.wechatbot.fashion.bot.message;

import com.wechatbot.fashion.ai.service.AiChatService;
import com.wechatbot.fashion.ai.service.LocalImageAssetStore;
import com.wechatbot.fashion.bot.audio.TtsVoiceSelectionService;
import com.wechatbot.fashion.bot.file.FileInstructionService;
import com.wechatbot.fashion.bot.file.FileSessionService;
import com.wechatbot.fashion.bot.runtime.ILinkRuntimeState;
import org.springframework.stereotype.Component;

/**
 * 处理微信消息中的固定文本命令（如"帮助""状态""清空"）。
 *
 * <p>命令仅匹配精确的文字输入，避免语音转写误触发。</p>
 */
@Component
public class CommandHandler {

    private final AiChatService aiChatService;
    private final TtsVoiceSelectionService voiceSelectionService;
    private final FileSessionService fileSessions;
    private final LocalImageAssetStore imageAssets;
    private final FileInstructionService fileInstructionService;

    public CommandHandler(
            AiChatService aiChatService,
            TtsVoiceSelectionService voiceSelectionService,
            FileSessionService fileSessions,
            LocalImageAssetStore imageAssets,
            FileInstructionService fileInstructionService
    ) {
        this.aiChatService = aiChatService;
        this.voiceSelectionService = voiceSelectionService;
        this.fileSessions = fileSessions;
        this.imageAssets = imageAssets;
        this.fileInstructionService = fileInstructionService;
    }

    /**
     * 处理固定命令。如果输入不是已知命令，返回 {@code null}。
     *
     * @param userId 当前用户 ID
     * @param prompt 已 trim 的用户输入
     * @param status 当前运行时快照
     * @return 命令回复文字，或 {@code null} 表示不是命令
     */
    public String handle(String userId, String prompt, ILinkRuntimeState.Snapshot status) {
        return switch (prompt.trim()) {
            case "帮助" -> """
                    支持的功能：
                    1. 发送普通文字与 AI 对话
                    2. 发送图片，并附上你的问题
                    3. 发送带微信转写文字的语音
                    4. 发送"状态"查看运行状态
                    5. 发送"清空"清除自己的上下文
                    6. 直接说"帮我画一张……"即可让 AI 自主判断是否生图
                    7. 直接说"请用语音回答……"即可生成 MP3 文件
                    8. 可直接说"有哪些音色""换成女声""恢复默认音色"
                    9. 发送 60 秒以内的短视频进行画面与语音联合分析
                    10. 发送文件后直接用普通话说明要求，例如"帮我把这个变成 PDF"
                    11. 不上传文件也可直接说"写一份 Word 报告"或"生成 Excel 表格"
                    """;
            case "状态" -> "微信连接：" + status.connectionStatus() + "\n"
                    + "长轮询：" + (status.polling() ? "运行中" : "未运行") + "\n"
                    + "AI：" + (aiChatService.isEnabled() ? "已启用" : "未启用") + "\n"
                    + "模型：" + (aiChatService.isEnabled() ? aiChatService.model() : "-") + "\n"
                    + "当前音色：" + voiceSelectionService.current(userId).display();
            case "清空" -> {
                aiChatService.clear(userId);
                fileSessions.clear(userId);
                imageAssets.clearCurrent(userId);
                fileInstructionService.clearCurrentDocumentPointer(userId);
                yield "已清空你的聊天记录和临时会话缓存；已保存的图片、文档版本和音色设置未删除";
            }
            default -> null;
        };
    }
}
