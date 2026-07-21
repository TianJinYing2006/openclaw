package com.example.demo.chat.command;

import com.example.demo.chat.command.ICommand;
import com.example.demo.wechat.ILinkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class StatusCommand implements ICommand {

    @Autowired
    @Lazy
    private ILinkService iLinkService;

    @Override
    public String getName() {
        return "status";
    }

    @Override
    public String getDescription() {
        return "显示程序运行状态";
    }

    @Override
    public String execute(String[] args) {
        String botId = iLinkService.getBotId();
        if (botId != null) {
            return "状态: ✅ 已连接\nBot ID: " + botId;
        } else {
            return "状态: ❌ 未登录";
        }
    }
}
