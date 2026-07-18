package com.example.demo.control;

import com.example.demo.service.ILinkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

/**
 * HTTP API 控制器 — 路由映射，直接委托给底层服务
 */
@RestController
@RequestMapping("/api")
public class MessageController {

    @Autowired
    private ILinkService iLinkService;

    @Autowired
    @Lazy
    private CommandHandler commandHandler;

    /**
     * 执行命令
     * GET /api/command?cmd=weather 北京
     */
    @GetMapping("/command")
    public String executeCommand(@RequestParam("cmd") String cmd) {
        String result = commandHandler.handle(cmd, "");
        return result != null ? result : "未知命令";
    }

    /**
     * 发送文本消息
     * GET /api/message/send?to=userId&text=hello
     */
    @GetMapping("/message/send")
    public String sendText(@RequestParam("to") String to,
                           @RequestParam("text") String text) {
        iLinkService.sendText(to, text);
        return "消息已发送至: " + to;
    }

    /**
     * 发送带输入态的消息
     * GET /api/message/sendWithTyping?to=userId&text=hello&typingMs=1500
     */
    @GetMapping("/message/sendWithTyping")
    public String sendWithTyping(@RequestParam("to") String to,
                                 @RequestParam("text") String text,
                                 @RequestParam(value = "typingMs", defaultValue = "1500") long typingMs) {
        iLinkService.sendTextWithTyping(to, text, typingMs);
        return "带输入态消息已发送至: " + to;
    }

    /**
     * 获取 Bot ID
     */
    @GetMapping("/message/botId")
    public String getBotId() {
        String botId = iLinkService.getBotId();
        return botId != null ? "Bot ID: " + botId : "尚未登录";
    }
}
