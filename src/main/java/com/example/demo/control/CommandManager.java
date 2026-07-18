package com.example.demo.control;

import com.example.demo.ICommand;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令管理器 — 注册所有 ICommand Bean，提供命令分发功能
 */
@Component
public class CommandManager {

    private static final Logger log = LoggerFactory.getLogger(CommandManager.class);
    private static final String VERSION = "1.0.0";

    private final Map<String, ICommand> commands = new LinkedHashMap<>();

    @Autowired(required = false)
    private List<ICommand> commandList;

    @PostConstruct
    public void init() {
        if (commandList != null) {
            for (ICommand cmd : commandList) {
                commands.put(cmd.getName(), cmd);
                log.debug("注册命令: /{}", cmd.getName());
            }
        }
        log.info("命令管理器初始化完成，共 {} 个命令", commands.size());
    }

    /**
     * 分发命令
     * @param input 原始输入文本
     * @return 命令执行结果；输入为空返回 null，未知命令返回错误提示
     */
    public String dispatch(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        String[] parts = trimmed.split("\\s+", 2);
        String cmdName = parts[0].toLowerCase();
        String[] args = parts.length > 1 ? parts[1].split("\\s+") : new String[0];

        ICommand cmd = commands.get(cmdName);
        if (cmd == null) {
            return null; // 非命令消息由调用方自行处理
        }
        try {
            return cmd.execute(args);
        } catch (Exception e) {
            log.error("命令 /{} 执行异常", cmdName, e);
            return "命令执行出错: " + resolveRootCauseMessage(e);
        }
    }

    /**
     * 获取所有命令的帮助文本
     */
    public String getHelpText() {
        StringBuilder sb = new StringBuilder("===== 可用命令 =====\n");
        for (ICommand cmd : commands.values()) {
            sb.append(String.format("  %-12s - %s\n", "/" + cmd.getName(), cmd.getDescription()));
        }
        sb.append("  %-12s - %s\n".formatted("/exit", "退出程序"));
        sb.append("====================");
        return sb.toString();
    }

    public String getVersion() {
        return "WeChat iLink Bot\n版本: " + VERSION;
    }

    private String resolveRootCauseMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg != null ? msg : cause.getClass().getSimpleName();
    }
}
