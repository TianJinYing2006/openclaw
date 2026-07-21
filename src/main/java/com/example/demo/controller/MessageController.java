package com.example.demo.controller;

import com.example.demo.ai.SpeechRecognitionService;
import com.example.demo.chat.CommandHandler;
import com.example.demo.chat.SessionManager;
import com.example.demo.wechat.ILinkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MessageController {

    @Autowired
    private ILinkService iLinkService;

    @Autowired
    @Lazy
    private CommandHandler commandHandler;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private SpeechRecognitionService speechRecognitionService;

    @GetMapping("/command")
    public String executeCommand(@RequestParam("cmd") String cmd) {
        String result = commandHandler.handle(cmd, "");
        return result != null ? result : "未知命令";
    }

    @GetMapping("/message/send")
    public String sendText(@RequestParam("to") String to,
                           @RequestParam("text") String text) {
        iLinkService.sendText(to, text);
        return "消息已发送至: " + to;
    }

    @GetMapping("/message/sendWithTyping")
    public String sendWithTyping(@RequestParam("to") String to,
                                 @RequestParam("text") String text,
                                 @RequestParam(value = "typingMs", defaultValue = "1500") long typingMs) {
        iLinkService.sendTextWithTyping(to, text, typingMs);
        return "带输入态消息已发送至: " + to;
    }

    @GetMapping("/message/sendVoiceReply")
    public String sendVoiceReply(@RequestParam("to") String to,
                                 @RequestParam("text") String text) {
        iLinkService.sendVoiceReply(to, text);
        return "语音回复已发送至: " + to;
    }

    // ===== 图片发送 =====

    @PostMapping("/message/sendImage")
    public ResponseEntity<String> sendImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("to") String to,
            @RequestParam(value = "caption", defaultValue = "") String caption) {
        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body("请选择要发送的图片");
            }
            if (file.getSize() > 10 * 1024 * 1024) {
                return ResponseEntity.badRequest().body("图片大小超过限制（最大 10MB）");
            }
            iLinkService.sendImageWithTyping(to, file.getBytes(), file.getOriginalFilename(), caption, 1500L);
            return ResponseEntity.ok("图片已发送至: " + to);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("发送图片失败: " + e.getMessage());
        }
    }

    @GetMapping("/message/sendImage")
    public String sendImageForm() {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>发送图片</title></head>"
                + "<body><h2>发送图片</h2>"
                + "<form method='POST' action='/api/message/sendImage' enctype='multipart/form-data'>"
                + "目标用户: <input type='text' name='to' required><br>"
                + "选择图片: <input type='file' name='file' accept='image/*' required><br>"
                + "说明文字: <input type='text' name='caption'><br>"
                + "<input type='submit' value='发送'></form></body></html>";
    }

    // ===== 语音发送 =====

    @PostMapping("/message/sendVoice")
    public ResponseEntity<String> sendVoice(
            @RequestParam("file") MultipartFile file,
            @RequestParam("to") String to,
            @RequestParam(value = "caption", defaultValue = "") String caption,
            @RequestParam(value = "playTimeMs", required = false) Integer playTimeMs,
            @RequestParam(value = "sampleRate", required = false) Integer sampleRate) {
        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body("请选择要发送的音频文件");
            }
            if (file.getSize() > 10 * 1024 * 1024) {
                return ResponseEntity.badRequest().body("音频大小超过限制（最大 10MB）");
            }
            iLinkService.sendVoiceWithTyping(to, file.getBytes(), file.getOriginalFilename(),
                    caption, playTimeMs, sampleRate, 1500L);
            return ResponseEntity.ok("语音已发送至: " + to);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("发送语音失败: " + e.getMessage());
        }
    }

    @GetMapping("/message/sendVoice")
    public String sendVoiceForm() {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>发送语音</title></head>"
                + "<body><h2>发送语音</h2>"
                + "<form method='POST' action='/api/message/sendVoice' enctype='multipart/form-data'>"
                + "目标用户: <input type='text' name='to' required><br>"
                + "选择音频: <input type='file' name='file' accept='audio/*' required><br>"
                + "说明文字: <input type='text' name='caption'><br>"
                + "语音时长(ms): <input type='number' name='playTimeMs' placeholder='可选'><br>"
                + "采样率(Hz): <input type='number' name='sampleRate' placeholder='可选'><br>"
                + "<input type='submit' value='发送'></form></body></html>";
    }

    @PostMapping("/speech/transcribe")
    public ResponseEntity<Map<String, String>> transcribeAudio(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dispatch", defaultValue = "false") boolean dispatch,
            @RequestParam(value = "userId", defaultValue = "http-user") String userId) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            if (file == null || file.isEmpty()) {
                result.put("error", "音频文件为空，请上传 wav、mp3、ogg、opus、amr 等音频文件");
                return ResponseEntity.badRequest().body(result);
            }

            byte[] audioBytes = file.getBytes();
            String mimeType = detectAudioMimeType(audioBytes, file.getContentType());
            String transcript = speechRecognitionService.transcribe(audioBytes, mimeType);
            result.put("text", transcript);

            if (dispatch) {
                String reply = commandHandler.handle(transcript, userId);
                result.put("reply", reply != null ? reply : "");
            }
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("error", "音频参数不正确：" + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (IOException e) {
            result.put("error", "语音识别服务暂时不可用，请检查音频格式、API Key 或配额后重试");
            return ResponseEntity.internalServerError().body(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.put("error", "语音识别请求被中断，请稍后重试");
            return ResponseEntity.internalServerError().body(result);
        } catch (Exception e) {
            result.put("error", "语音识别失败，请稍后重试");
            return ResponseEntity.internalServerError().body(result);
        }
    }

    @GetMapping("/message/botId")
    public String getBotId() {
        String botId = iLinkService.getBotId();
        return botId != null ? "Bot ID: " + botId : "尚未登录";
    }

    @GetMapping("/sessions/count")
    public Map<String, Integer> getSessionCount() {
        return Map.of("count", sessionManager.getActiveSessionCount());
    }

    @DeleteMapping("/sessions/{userId}")
    public ResponseEntity<Void> clearSession(@PathVariable String userId) {
        if (!sessionManager.hasSession(userId)) {
            return ResponseEntity.notFound().build();
        }
        sessionManager.clearSession(userId);
        return ResponseEntity.noContent().build();
    }

    private String detectAudioMimeType(byte[] bytes, String contentType) {
        if (contentType != null && !contentType.isBlank() && !"application/octet-stream".equals(contentType)) {
            return contentType;
        }
        if (bytes.length >= 4 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return "audio/wav";
        }
        if (bytes.length >= 4 && bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S') {
            return "audio/ogg";
        }
        if (bytes.length >= 3 && bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return "audio/mpeg";
        }
        if (bytes.length >= 6 && bytes[0] == '#' && bytes[1] == '!' && bytes[2] == 'A'
                && bytes[3] == 'M' && bytes[4] == 'R') {
            return "audio/amr";
        }
        return "audio/opus";
    }
}
