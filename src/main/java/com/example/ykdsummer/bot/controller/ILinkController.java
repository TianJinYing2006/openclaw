package com.example.ykdsummer.bot.controller;

import com.example.ykdsummer.bot.runtime.ILinkRuntimeState;
import com.example.ykdsummer.bot.service.ILinkBotService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

/**
 * 提供给浏览器、PowerShell 或 Postman 使用的本地教学/管理接口。
 *
 * <p>这里没有实现 iLink 通信协议。Controller 只接收 HTTP 请求、检查参数，然后调用
 * {@link ILinkBotService}。真正的 SDK 启动、消息轮询和回复都在 Service 中。</p>
 */
@RestController
@RequestMapping("/api/ilink")
public class ILinkController {

    private final ILinkBotService botService;

    public ILinkController(ILinkBotService botService) {
        this.botService = botService;
    }

    /**
     * 查看当前连接状态和收发计数。
     *
     * @return 可公开观察的运行快照；不会返回登录 token 或 contextToken
     */
    @GetMapping("/status")
    public ILinkRuntimeState.Snapshot status() {
        return botService.status();
    }

    /**
     * 打开当前登录二维码。
     *
     * <p>SDK 产生二维码以后，地址会暂存在运行状态中，本接口使用 302 跳转到该地址。
     * 已经登录时二维码会被清除，此时返回 404 是正常现象，并不代表程序故障。</p>
     */
    @GetMapping("/qrcode")
    public ResponseEntity<?> qrcode() {
        String qrCodeUrl = botService.status().qrCodeUrl();
        if (qrCodeUrl == null || qrCodeUrl.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "当前没有待扫描二维码，请先启用 iLink 或查看连接状态"));
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(qrCodeUrl)).build();
    }

    /**
     * 主动发送一条文字消息，主要用于联调。
     *
     * <p>它和“回复刚收到的消息”不同：主动发送没有现成的入站消息对象，因此调用者必须
     * 同时提供目标用户 {@code toUserId} 和会话上下文 {@code contextToken}。</p>
     *
     * @param request JSON 请求体，包含接收者、上下文令牌和文字
     */
    @PostMapping("/send")
    public ResponseEntity<?> send(@RequestBody SendTextRequest request) {
        if (request == null || isBlank(request.toUserId()) || isBlank(request.contextToken()) || isBlank(request.text())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message",
                    "toUserId、contextToken 和 text 都不能为空"
            ));
        }
        botService.sendText(request.toUserId(), request.contextToken(), request.text());
        return ResponseEntity.ok(Map.of("sent", true));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 主动发送接口的请求结构；record 只负责承载数据。 */
    public record SendTextRequest(String toUserId, String contextToken, String text) {
    }
}
