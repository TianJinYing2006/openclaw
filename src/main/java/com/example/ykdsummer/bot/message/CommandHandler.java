package com.example.ykdsummer.bot.message;

import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.bot.audio.TtsVoiceSelectionService;
import com.example.ykdsummer.bot.file.FileSessionService;
import com.example.ykdsummer.bot.runtime.ILinkRuntimeState;
import org.springframework.stereotype.Component;

/**
 * 固定命令处理器。处理 exact-match 的命令行。
 *
 * <p>职责：只处理"帮助"、"状态"、"清空"、"音色"等本地命令，
 * 不涉及任何 LLM 调用。返回 {@code null} 表示不是本类处理的固定命令。</p>
 */
@Component
public class CommandHandler {

    private final AiChatService aiChatService;
    private final TtsVoiceSelectionService voiceSelectionService;
    private final FileSessionService fileSessions;

    public CommandHandler(
            AiChatService aiChatService,
            TtsVoiceSelectionService voiceSelectionService,
            FileSessionService fileSessions
    ) {
        this.aiChatService = aiChatService;
        this.voiceSelectionService = voiceSelectionService;
        this.fileSessions = fileSessions;
    }

    /**
     * @param userId 用户 ID
     * @param prompt 用户输入的完整文字
     * @param status 运行时状态快照
     * @return 命令的回复文本，或 null 表示不是固定命令
     */
    public String handle(String userId, String prompt, ILinkRuntimeState.Snapshot status) {
        String command = prompt.trim();

        // 先检查音色系列命令（前缀匹配）
        String voiceCommandReply = handleVoiceCommand(userId, command);
        if (voiceCommandReply != null) {
            return voiceCommandReply;
        }

        return switch (command) {
            case "帮助" -> "支持的功能：\n"
                    + "1. 发送普通文字与 AI 对话\n"
                    + "2. 发送图片，并附上你的问题\n"
                    + "3. 发送带微信转写文字的语音\n"
                    + "4. 发送“状态”查看运行状态\n"
                    + "5. 发送“清空”清除自己的上下文\n"
                    + "6. 发送“生图：画面描述”强制让 AI 生图\n"
                    + "7. 发送“语音：问题”获取 MP3 语音文件\n"
                    + "8. 发送“音色列表”查看可用音色\n"
                    + "9. 发送“设置音色：龙婉”实时切换音色\n"
                    + "10. 发送“当前音色”或“重置音色”\n"
                    + "11. 发送 60 秒以内的短视频进行画面与语音联合分析\n"
                    + "12. 发送文件后直接用普通话说明要求，例如“帮我把这个变成 PDF”\n"
                    + "13. 不上传文件也可直接说“写一份 Word 报告”或“生成 Excel 表格”";
            case "状态" -> "微信连接：" + status.connectionStatus() + "\n"
                    + "长轮询：" + (status.polling() ? "运行中" : "未运行") + "\n"
                    + "AI：" + (aiChatService.isEnabled() ? "已启用" : "未启用") + "\n"
                    + "模型：" + (aiChatService.isEnabled() ? aiChatService.model() : "-") + "\n"
                    + "当前音色：" + voiceSelectionService.current(userId).display();
            case "清空" -> {
                aiChatService.clear(userId);
                fileSessions.clear(userId);
                yield "已清空你的聊天记录";
            }
            default -> null;
        };
    }

    private String handleVoiceCommand(String userId, String command) {
        if ("音色列表".equals(command)) {
            return voiceSelectionService.listMessage(userId);
        }
        if ("当前音色".equals(command)) {
            return "当前音色：" + voiceSelectionService.current(userId).display();
        }
        if ("重置音色".equals(command) || "恢复默认音色".equals(command)) {
            return "已恢复默认音色：" + voiceSelectionService.reset(userId).display();
        }
        if (command.matches("^(设置|切换|更换|换)音色[：:].+")) {
            String requested = command.replaceFirst("^(设置|切换|更换|换)音色[：:]+", "").trim();
            return voiceSelectionService.select(userId, requested)
                    .map(option -> "已切换音色：" + option.display() + "。下一条“语音：问题”立即生效。")
                    .orElse("没有找到音色“" + requested + "”。发送“音色列表”查看可用音色。");
        }
        return null;
    }
}
