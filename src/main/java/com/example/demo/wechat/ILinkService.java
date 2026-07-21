package com.example.demo.wechat;

import com.example.demo.ai.ImageGenerationService;
import com.example.demo.ai.FileRecognitionService;
import com.example.demo.ai.FileGenerationService;
import com.example.demo.ai.LLMService;
import com.example.demo.ai.SpeechRecognitionService;
import com.example.demo.ai.SpeechSynthesisService;
import com.example.demo.ai.VideoUnderstandingService;
import com.example.demo.chat.CommandHandler;
import com.example.demo.chat.SessionManager;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.FileItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Service
public class ILinkService {

    private static final Logger log = LoggerFactory.getLogger(ILinkService.class);
    private static final String WEIXIN_HOST = "ilinkai.weixin.qq.com";
    private static final int WEIXIN_PORT = 443;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_AUDIO_BYTES = 20 * 1024 * 1024;
    private static final int MAX_VIDEO_BYTES = 7 * 1024 * 1024;
    private static final String IMAGE_GENERATION_PREFIX = "\u751f\u56fe\uff1a";
    private static final String IMAGE_GENERATION_PREFIX_ASCII = "\u751f\u56fe:";
    private static final String VOICE_REPLY_PREFIX = "\u8bed\u97f3\u56de\u590d:";
    private static final String VOICE_REPLY_PREFIX_FULL_WIDTH = "\u8bed\u97f3\u56de\u590d\uff1a";
    private static final String VOICE_REPLY_SHORT_PREFIX = "\u8bed\u97f3:";
    private static final String VOICE_REPLY_SHORT_PREFIX_FULL_WIDTH = "\u8bed\u97f3\uff1a";
    private static final String VOICE_SETTING_PREFIX = "\u8bbe\u7f6e\u97f3\u8272:";
    private static final String VOICE_SETTING_PREFIX_FULL_WIDTH = "\u8bbe\u7f6e\u97f3\u8272\uff1a";
    private static final Pattern VOICE_REPLY_WITH_VOICE_PATTERN = Pattern.compile(
            "^\u8bed\u97f3\u56de\u590d\\s*\\[\\s*([A-Za-z0-9_-]{1,80})\\s*]\\s*[:\uff1a]\\s*(.*)$");
    private static final Pattern GENERATE_FILE_PATTERN = Pattern.compile(
            "^/gen\\s+(txt|pdf|docx|xlsx)\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    private ILinkClient client;
    private LoginContext loginContext;
    private final CountDownLatch loginLatch = new CountDownLatch(1);

    @Autowired
    private CommandHandler commandHandler;

    @Autowired
    private ImageGenerationService imageGenerationService;

    @Autowired
    private SpeechRecognitionService speechRecognitionService;

    @Autowired
    private SpeechSynthesisService speechSynthesisService;

    @Autowired
    private VideoUnderstandingService videoUnderstandingService;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private FileRecognitionService fileRecognitionService;

    @Autowired
    private FileGenerationService fileGenerationService;

    @Autowired
    private LLMService llmService;

    @PostConstruct
    public void init() {
        if (!networkPrecheck()) {
            log.warn("Network precheck failed, skip iLink initialization");
            return;
        }

        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(35000)
                .readTimeoutMs(35000)
                .writeTimeoutMs(35000)
                .httpMaxRetries(3)
                .retryBaseDelayMs(1000)
                .retryMaxDelayMs(10000)
                .heartbeatEnabled(true)
                .heartbeatIntervalMs(30000)
                .channelVersion("1.0.0")
                .build();

        client = ILinkClient.builder()
                .config(config)
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        loginContext = context;
                        loginLatch.countDown();
                        log.info("Login success, botId={}", context.getBotId());
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        loginLatch.countDown();
                        log.error("Login failed: {}", resolveRootCauseMessage(throwable));
                    }
                })
                .onMessage(new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        for (WeixinMessage msg : messages) {
                            handleIncomingMessage(msg);
                        }
                    }
                })
                .build();

        Thread loginThread = new Thread(this::doLogin, "ilink-login");
        loginThread.setDaemon(true);
        loginThread.start();
    }

    private void doLogin() {
        try {
            String qrCodeContent = client.executeLogin();
            log.info("QR code login content generated");
            System.out.println("========== Please scan this QR code content with WeChat ==========");
            System.out.println(qrCodeContent);
            System.out.println("=================================================================");

            loginContext = client.getLoginFuture().get(120, TimeUnit.SECONDS);
            log.info("Login completed, botId={}", loginContext.getBotId());

            List<WeixinMessage> messages = client.getUpdates();
            log.info("Initial unread message count={}", messages.size());
            for (WeixinMessage msg : messages) {
                handleIncomingMessage(msg);
            }
        } catch (Exception e) {
            log.error("Initialize iLink client failed: {}", resolveRootCauseMessage(e));
        }
    }

    private boolean networkPrecheck() {
        log.info("Network precheck started");
        try {
            InetAddress[] addresses = InetAddress.getAllByName(WEIXIN_HOST);
            log.info("DNS resolved: {} -> {}", WEIXIN_HOST, (Object) addresses);
        } catch (UnknownHostException e) {
            log.error("DNS resolve failed: {}", WEIXIN_HOST);
            return false;
        }

        try (Socket socket = new Socket(WEIXIN_HOST, WEIXIN_PORT)) {
            log.info("TCP connected: {}:{}", WEIXIN_HOST, WEIXIN_PORT);
        } catch (Exception e) {
            log.error("TCP connect failed: {}:{}, {}", WEIXIN_HOST, WEIXIN_PORT, resolveRootCauseMessage(e));
            return false;
        }
        return true;
    }

    private void handleIncomingMessage(WeixinMessage msg) {
        log.info("Received message from={}", msg.getFrom_user_id());
        if (msg.getItem_list() == null || msg.getItem_list().isEmpty()) {
            return;
        }

        StringBuilder textBuilder = new StringBuilder();
        MessageItem imageItem = null;
        MessageItem voiceItem = null;
        MessageItem videoItem = null;
        MessageItem fileItem = null;
        for (MessageItem item : msg.getItem_list()) {
            if (item.getText_item() != null && item.getText_item().getText() != null) {
                if (textBuilder.length() > 0) {
                    textBuilder.append('\n');
                }
                textBuilder.append(item.getText_item().getText());
            }
            if (item.getImage_item() != null) {
                imageItem = item;
            }
            if (item.getVoice_item() != null) {
                voiceItem = item;
            }
            if (item.getVideo_item() != null) {
                videoItem = item;
            }
            if (item.getFile_item()!=null) {
                fileItem = item;
            }
        }

        String text = textBuilder.toString().trim();
        String fromUserId = msg.getFrom_user_id();
        if (isImageGenerationPrompt(text)) {
            handleImageGeneration(fromUserId, text);
            return;
        }
        if (voiceItem != null) {
            handleIncomingVoice(voiceItem, fromUserId);
            return;
        }
        if (videoItem != null) {
            handleIncomingVideo(videoItem, fromUserId, text);
            return;
        }
        if (imageItem != null) {
            handleIncomingImage(imageItem, fromUserId, text);
            return;
        }
        if (fileItem != null) {
            handleIncomingFile(fileItem, fromUserId, text);
            return;
        }

        // /gen <格式> <主题> → 生成文件发微信
        if (!text.isEmpty()) {
            java.util.regex.Matcher genMatcher = GENERATE_FILE_PATTERN.matcher(text);
            if (genMatcher.matches()) {
                handleGenerateFile(genMatcher, fromUserId);
                return;
            }
        }

        if (!text.isEmpty()) {
            log.info("Received text: from={}, text={}", fromUserId, text);
            System.out.println("[" + fromUserId + "] text: " + text);

            String voicePreference = extractVoicePreference(text);
            if (voicePreference != null) {
                if (voicePreference.isBlank()) {
                    sendTextWithTyping(fromUserId, "\u7528\u6cd5\uff1a\u8bbe\u7f6e\u97f3\u8272\uff1aCherry", 800);
                    return;
                }
                sessionManager.setVoicePreference(fromUserId, voicePreference);
                sendTextWithTyping(fromUserId, "\u5df2\u8bbe\u7f6e\u9ed8\u8ba4\u97f3\u8272\uff1a" + voicePreference, 800);
                return;
            }

            VoiceReplyRequest voiceReplyRequest = parseVoiceReplyRequest(text);
            boolean voiceReplyRequested = voiceReplyRequest != null;
            String userText = voiceReplyRequested ? voiceReplyRequest.text() : text;
            if (userText.isBlank()) {
                sendTextWithTyping(fromUserId,
                        "\u7528\u6cd5\uff1a\u8bed\u97f3\u56de\u590d\uff1a\u8bf7\u4ecb\u7ecd\u4e00\u4e0b Spring Boot\uff0c\u6216\u8bed\u97f3\u56de\u590d[Cherry]\uff1a\u4f60\u597d", 800);
                return;
            }
            String reply = commandHandler.handle(userText, fromUserId, voiceReplyRequested);
            if (reply != null) {
                if (voiceReplyRequested) {
                    sendVoiceReply(fromUserId, reply, voiceReplyRequest.voice());
                } else {
                    sendTextWithTyping(fromUserId, reply, 1500);
                }
            }
        }
    }

    private boolean isImageGenerationPrompt(String text) {
        return text != null && (text.startsWith(IMAGE_GENERATION_PREFIX) || text.startsWith(IMAGE_GENERATION_PREFIX_ASCII));
    }

    private String extractVoicePreference(String text) {
        if (text == null) {
            return null;
        }
        for (String prefix : List.of(VOICE_SETTING_PREFIX, VOICE_SETTING_PREFIX_FULL_WIDTH)) {
            if (text.startsWith(prefix)) {
                return text.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private VoiceReplyRequest parseVoiceReplyRequest(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = VOICE_REPLY_WITH_VOICE_PATTERN.matcher(text);
        if (matcher.matches()) {
            return new VoiceReplyRequest(matcher.group(2).trim(), matcher.group(1).trim());
        }
        for (String prefix : List.of(
                VOICE_REPLY_PREFIX,
                VOICE_REPLY_PREFIX_FULL_WIDTH,
                VOICE_REPLY_SHORT_PREFIX,
                VOICE_REPLY_SHORT_PREFIX_FULL_WIDTH)) {
            if (text.startsWith(prefix)) {
                return new VoiceReplyRequest(text.substring(prefix.length()).trim(), null);
            }
        }
        return null;
    }

    private void handleImageGeneration(String fromUserId, String text) {
        String prompt = text.startsWith(IMAGE_GENERATION_PREFIX)
                ? text.substring(IMAGE_GENERATION_PREFIX.length()).trim()
                : text.substring(IMAGE_GENERATION_PREFIX_ASCII.length()).trim();
        if (prompt.isEmpty()) {
            sendTextWithTyping(fromUserId, "\u7528\u6cd5\uff1a\u751f\u56fe\uff1a\u4e00\u53ea\u5750\u5728\u7a97\u8fb9\u7684\u6a58\u732b", 800);
            return;
        }

        log.info("Start image generation: from={}, prompt={}", fromUserId, prompt);
        sendTextWithTyping(fromUserId, "\u6b63\u5728\u6839\u636e\u63d0\u793a\u8bcd\u751f\u6210\u56fe\u7247\uff0c\u8bf7\u7a0d\u5019...", 800);
        try {
            byte[] imageBytes = imageGenerationService.generate(prompt);
            sendImage(fromUserId, imageBytes, "generated.png", prompt);
        } catch (Exception e) {
            log.error("Image generation failed: from={}, error={}", fromUserId, resolveRootCauseMessage(e), e);
            sendTextWithTyping(fromUserId, "\u56fe\u7247\u751f\u6210\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5 API Key\u3001\u6a21\u578b\u914d\u7f6e\u6216\u7a0d\u540e\u91cd\u8bd5", 800);
        }
    }

    private void handleIncomingVideo(MessageItem videoItem, String fromUserId, String caption) {
        try {
            if (videoItem.getVideo_item().getMedia() == null) {
                throw new IllegalStateException("Video message does not contain original media");
            }
            Long declaredSize = videoItem.getVideo_item().getVideo_size();
            if (declaredSize != null && declaredSize > MAX_VIDEO_BYTES) {
                sendTextWithTyping(fromUserId, "\u89c6\u9891\u592a\u5927\uff0c\u8bf7\u53d1\u9001\u4e0d\u8d85\u8fc7 7MB \u7684\u89c6\u9891", 800);
                return;
            }

            sendTextWithTyping(fromUserId, "\u6b63\u5728\u4e0b\u8f7d\u5e76\u5206\u6790\u89c6\u9891\uff0c\u8bf7\u7a0d\u5019...", 800);
            byte[] videoBytes = client.downloadVideoFromMessageItem(videoItem);
            if (videoBytes == null || videoBytes.length == 0) {
                throw new IllegalStateException("Video download result is empty");
            }
            if (videoBytes.length > MAX_VIDEO_BYTES) {
                sendTextWithTyping(fromUserId, "\u89c6\u9891\u592a\u5927\uff0c\u8bf7\u53d1\u9001\u4e0d\u8d85\u8fc7 7MB \u7684\u89c6\u9891", 800);
                return;
            }

            String prompt = caption == null || caption.isBlank() ? "\u8bf7\u5206\u6790\u8fd9\u4e2a\u89c6\u9891\u7684\u4e3b\u8981\u5185\u5bb9\u3002" : caption;
            String reply = videoUnderstandingService.analyze(
                    sessionManager.getHistory(fromUserId), prompt, videoBytes, detectVideoMimeType(videoBytes));
            if (reply != null) {
                sendTextWithTyping(fromUserId, reply, 1500);
            }
        } catch (Exception e) {
            log.error("Video understanding failed: from={}, error={}", fromUserId, resolveRootCauseMessage(e), e);
            sendTextWithTyping(fromUserId, "\u89c6\u9891\u8bc6\u522b\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5", 800);
        }
    }

    private void handleIncomingFile(MessageItem fileItem, String fromUserId, String caption) {
        try {
            FileItem fi = fileItem.getFile_item();
            String fileName = fi.getFile_name();
            long fileSize = Long.parseLong(fi.getLen());

            log.info("Received file: from={}, name={}, size={}bytes", fromUserId, fileName, fileSize);

            // 大小限制（20MB）
            if (fileSize > 20 * 1024 * 1024) {
                sendTextWithTyping(fromUserId, "文件太大了，请发送不超过 20MB 的文件", 800);
                return;
            }

            // 下载文件
            sendTextWithTyping(fromUserId, "正在接收文件，请稍候...", 600);
            byte[] fileBytes = client.downloadFileFromMessageItem(fileItem);

            // 识别文件类型并提取文本
            FileRecognitionService.FileContent content = fileRecognitionService.extract(fileBytes, fileName);

            if (!content.isText()) {
                sendTextWithTyping(fromUserId,
                        "收到一个 " + content.type() + " 文件（" + formatFileSize(content.fileSize()) + "），"
                        + "暂不支持解析此格式。目前支持：txt / md / csv / json / xml / yml / pdf / docx / xlsx / pptx 等文档格式",
                        800);
                return;
            }

            // 构建提示词交给 LLM
            String prompt = fileRecognitionService.buildPrompt(fileName, content.text(), caption);
            String reply = commandHandler.handle(prompt, fromUserId);
            if (reply != null) {
                // 在回复前加个文件摘要头
                String header = "📄 " + fileName + "（" + formatFileSize(content.fileSize()) + "，"
                        + content.text().length() + "字符）\n\n";
                sendTextWithTyping(fromUserId, header + reply, 2000);
            }
        } catch (Exception e) {
            log.error("File handling failed: from={}, error={}", fromUserId, resolveRootCauseMessage(e), e);
            sendTextWithTyping(fromUserId, "文件处理失败，请稍后重试", 800);
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private void handleGenerateFile(java.util.regex.Matcher matcher, String fromUserId) {
        String format = matcher.group(1).toLowerCase();
        String topic = matcher.group(2).trim();

        log.info("File generation requested: from={}, format={}, topic={}", fromUserId, format, topic);
        sendTextWithTyping(fromUserId, "正在生成 " + format.toUpperCase() + " 文件，请稍候...", 800);

        try {
            // 构建提示词让 LLM 生成内容
            String prompt = switch (format) {
                case "xlsx" ->
                    "请生成关于'" + topic + "'的表格数据，格式要求如下：\n"
                    + "第一行为表头（列名用中文），之后每行为一条数据记录\n"
                    + "列之间用制表符（\\t）分隔\n"
                    + "请直接输出数据，不要带任何额外说明";
                default ->
                    "请生成一篇关于'" + topic + "'的内容，要求内容丰富、结构完整。"
                    + "请直接输出正文内容，不要带任何额外说明。";
            };

            String content = llmService.chat(prompt);
            if (content == null || content.isBlank()) {
                sendTextWithTyping(fromUserId, "生成内容为空，请稍后重试", 800);
                return;
            }

            byte[] fileBytes;
            String fileName;
            switch (format) {
                case "pdf" -> {
                    fileBytes = fileGenerationService.generatePdf(content, topic);
                    fileName = topic.replaceAll("[\\\\/:*?\"<>|]", "_") + ".pdf";
                    if (fileBytes.length < 10) {
                        // PDF 生成失败（降级），使用 TXT 文件名
                        fileName = topic.replaceAll("[\\\\/:*?\"<>|]", "_") + ".txt";
                    }
                }
                case "docx" -> {
                    fileBytes = fileGenerationService.generateDocx(content, topic);
                    fileName = topic.replaceAll("[\\\\/:*?\"<>|]", "_") + ".docx";
                    if (fileBytes.length < 10) {
                        fileName = topic.replaceAll("[\\\\/:*?\"<>|]", "_") + ".txt";
                    }
                }
                case "xlsx" -> {
                    var rows = fileGenerationService.parseTableFromContent(content);
                    if (rows.size() < 2) {
                        // LLM 没输出表格格式，直接当成 TXT
                        fileBytes = fileGenerationService.generateTxt(content);
                        fileName = topic.replaceAll("[\\\\/:*?\"<>|]", "_") + ".txt";
                    } else {
                        fileBytes = fileGenerationService.generateXlsx(rows, topic);
                        fileName = topic.replaceAll("[\\\\/:*?\"<>|]", "_") + ".xlsx";
                        if (fileBytes.length < 10) {
                            fileName = topic.replaceAll("[\\\\/:*?\"<>|]", "_") + ".txt";
                        }
                    }
                }
                default -> {
                    fileBytes = fileGenerationService.generateTxt(content);
                    fileName = topic.replaceAll("[\\\\/:*?\"<>|]", "_") + ".txt";
                }
            }

            if (fileBytes == null || fileBytes.length == 0) {
                sendTextWithTyping(fromUserId, "文件生成失败，请稍后重试", 800);
                return;
            }

            sendFileWithTyping(fromUserId, fileBytes, fileName,
                    "已生成 " + format.toUpperCase() + " 文件：《" + topic + "》", 1500);
            log.info("File generated and sent: to={}, fileName={}, size={}bytes",
                    fromUserId, fileName, fileBytes.length);
        } catch (Exception e) {
            log.error("File generation failed: from={}, format={}, topic={}, error={}",
                    fromUserId, format, topic, resolveRootCauseMessage(e), e);
            sendTextWithTyping(fromUserId, "文件生成失败：" + resolveRootCauseMessage(e), 800);
        }
    }

    public void sendFileWithTyping(String targetUserId, byte[] fileBytes, String fileName, String caption, long typingMs) {
        if (client == null || loginContext == null) {
            log.warn("Client is not ready, cannot send file");
            return;
        }
        if (fileBytes == null || fileBytes.length == 0) {
            log.warn("File bytes are empty, skip sending");
            return;
        }
        try {
            if (caption != null && !caption.isBlank()) {
                client.sendTextWithTyping(targetUserId, caption, typingMs);
            }
            client.sendFile(targetUserId, fileBytes, fileName, caption);
            log.info("File sent: to={}, fileName={}, size={}bytes", targetUserId, fileName, fileBytes.length);
        } catch (Exception e) {
            log.error("Send file failed: {}", resolveRootCauseMessage(e));
        }
    }

    private String detectVideoMimeType(byte[] bytes) {
        if (bytes.length >= 12
                && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
            return "video/mp4";
        }
        if (bytes.length >= 4
                && (bytes[0] & 0xff) == 0x1a && (bytes[1] & 0xff) == 0x45
                && (bytes[2] & 0xff) == 0xdf && (bytes[3] & 0xff) == 0xa3) {
            return "video/webm";
        }
        return "video/mp4";
    }

    private void handleIncomingVoice(MessageItem voiceItem, String fromUserId) {
        try {
            VoiceItem incomingVoice = voiceItem.getVoice_item();
            String transcript = incomingVoice.getText();
            if (transcript == null || transcript.isBlank()) {
                byte[] audioBytes = client.downloadVoiceFromMessageItem(voiceItem);
                if (audioBytes == null || audioBytes.length == 0) {
                    throw new IllegalStateException("Audio download result is empty");
                }
                if (audioBytes.length > MAX_AUDIO_BYTES) {
                    sendTextWithTyping(fromUserId, "\u8bed\u97f3\u6587\u4ef6\u592a\u5927\uff0c\u8bf7\u53d1\u9001\u4e0d\u8d85\u8fc7 20MB \u7684\u8bed\u97f3", 800);
                    return;
                }
                String mimeType = detectAudioMimeType(audioBytes);
                log.info("Start ASR: from={}, size={}bytes, mime={}", fromUserId, audioBytes.length, mimeType);
                sendTextWithTyping(fromUserId, "\u6b63\u5728\u8bc6\u522b\u8bed\u97f3\uff0c\u8bf7\u7a0d\u5019...", 600);
                transcript = speechRecognitionService.transcribe(audioBytes, mimeType);
            } else {
                log.info("Use WeChat voice transcript: from={}", fromUserId);
            }

            transcript = transcript.trim();
            if (transcript.isEmpty()) {
                sendTextWithTyping(fromUserId, "\u6ca1\u6709\u8bc6\u522b\u5230\u6709\u6548\u8bed\u97f3\u5185\u5bb9", 800);
                return;
            }

            log.info("ASR result: from={}, text={}", fromUserId, transcript);
            System.out.println("[" + fromUserId + "] voice: " + transcript);
            String reply = commandHandler.handle(transcript, fromUserId);
            if (reply != null) {
                String labeled = "\u3010\u8bed\u97f3\u8bc6\u522b\u56de\u590d\u3011" + reply;
                sendTextWithTyping(fromUserId, labeled, 800);
            }
        } catch (Exception e) {
            log.error("ASR failed: from={}, error={}", fromUserId, resolveRootCauseMessage(e), e);
            sendTextWithTyping(fromUserId, "\u8bed\u97f3\u8bc6\u522b\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5", 800);
        }
    }

    private String detectAudioMimeType(byte[] bytes) {
        if (bytes.length >= 4
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return "audio/wav";
        }
        if (bytes.length >= 4
                && bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S') {
            return "audio/ogg";
        }
        if (bytes.length >= 3
                && bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return "audio/mpeg";
        }
        if (bytes.length >= 6
                && bytes[0] == '#' && bytes[1] == '!' && bytes[2] == 'A'
                && bytes[3] == 'M' && bytes[4] == 'R') {
            return "audio/amr";
        }
        return "audio/opus";
    }

    private void handleIncomingImage(MessageItem imageItem, String fromUserId, String caption) {
        try {
            byte[] imageBytes = imageItem.getImage_item().getMedia() != null
                    ? client.downloadImageFromMessageItem(imageItem)
                    : client.downloadImageThumbFromMessageItem(imageItem);
            if (imageBytes == null || imageBytes.length == 0) {
                throw new IllegalStateException("Image download result is empty");
            }
            if (imageBytes.length > MAX_IMAGE_BYTES) {
                log.warn("Image too large: from={}, size={}bytes", fromUserId, imageBytes.length);
                sendTextWithTyping(fromUserId, "\u56fe\u7247\u592a\u5927\u4e86\uff0c\u8bf7\u53d1\u9001\u4e0d\u8d85\u8fc7 10MB \u7684\u56fe\u7247", 800);
                return;
            }

            String mimeType = detectImageMimeType(imageBytes);
            log.info("Received image: from={}, size={}bytes, mime={}", fromUserId, imageBytes.length, mimeType);
            System.out.println("[" + fromUserId + "] image: " + imageBytes.length + " bytes, " + mimeType);

            String reply = commandHandler.handle(caption, fromUserId, imageBytes, mimeType);
            if (reply != null) {
                sendTextWithTyping(fromUserId, reply, 1500);
            }
        } catch (Exception e) {
            log.error("Image handling failed: from={}, error={}", fromUserId, resolveRootCauseMessage(e), e);
            sendTextWithTyping(fromUserId, "\u56fe\u7247\u63a5\u6536\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5", 800);
        }
    }

    private String detectImageMimeType(byte[] bytes) {
        if (bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47) {
            return "image/png";
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (bytes.length >= 6) {
            String header = new String(bytes, 0, 6, StandardCharsets.US_ASCII);
            if (header.equals("GIF87a") || header.equals("GIF89a")) {
                return "image/gif";
            }
        }
        if (bytes.length >= 12
                && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    public void sendText(String targetUserId, String text) {
        if (client == null || loginContext == null) {
            log.warn("Client is not ready, cannot send text");
            return;
        }
        try {
            client.sendText(targetUserId, text);
            log.info("Text sent: to={}, text={}", targetUserId, text);
        } catch (Exception e) {
            log.error("Send text failed: {}", resolveRootCauseMessage(e));
        }
    }

    public void sendTextWithTyping(String targetUserId, String text, long typingMs) {
        if (client == null || loginContext == null) {
            log.warn("Client is not ready, cannot send text");
            return;
        }
        try {
            client.sendTextWithTyping(targetUserId, text, typingMs);
            log.info("Typing text sent: to={}, text={}", targetUserId, text);
        } catch (Exception e) {
            log.error("Send typing text failed: {}", resolveRootCauseMessage(e));
        }
    }

    public void sendImage(String targetUserId, byte[] imageBytes, String fileName, String caption) {
        if (client == null || loginContext == null) {
            log.warn("Client is not ready, cannot send image");
            return;
        }
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("Image bytes are empty, skip sending");
            return;
        }
        try {
            client.sendImage(targetUserId, imageBytes, fileName, caption);
            log.info("Image sent: to={}, fileName={}, size={}bytes", targetUserId, fileName, imageBytes.length);
        } catch (Exception e) {
            log.error("Send image failed: {}", resolveRootCauseMessage(e));
        }
    }

    public void sendImageWithTyping(String targetUserId, byte[] imageBytes, String fileName, String caption, long typingMs) {
        if (client == null || loginContext == null) {
            log.warn("Client is not ready, cannot send image");
            return;
        }
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("Image bytes are empty, skip sending");
            return;
        }
        try {
            if (caption != null && !caption.isBlank()) {
                client.sendTextWithTyping(targetUserId, caption, typingMs);
            }
            client.sendImage(targetUserId, imageBytes, fileName, caption);
            log.info("Image sent: to={}, fileName={}, size={}bytes", targetUserId, fileName, imageBytes.length);
        } catch (Exception e) {
            log.error("Send image failed: {}", resolveRootCauseMessage(e));
        }
    }

    public void sendVoice(String targetUserId, byte[] voiceBytes, String fileName, Integer playTimeMs, Integer sampleRate) {
        if (client == null || loginContext == null) {
            log.warn("Client is not ready, cannot send voice");
            return;
        }
        if (voiceBytes == null || voiceBytes.length == 0) {
            log.warn("Voice bytes are empty, skip sending");
            return;
        }
        try {
            client.sendVoice(targetUserId, voiceBytes, fileName, playTimeMs, sampleRate);
            log.info("Voice sent: to={}, fileName={}, size={}bytes", targetUserId, fileName, voiceBytes.length);
        } catch (Exception e) {
            log.error("Send voice failed: {}", resolveRootCauseMessage(e));
        }
    }

    /**
     * 先显示输入态再发送语音消息
     * @param targetUserId 目标用户 ID
     * @param voiceBytes   语音字节数据
     * @param fileName     文件名
     * @param caption      文字说明
     * @param playTimeMs   语音时长（毫秒）
     * @param sampleRate   采样率（Hz）
     * @param typingMs     输入态显示时长（毫秒）
     */
    public void sendVoiceWithTyping(String targetUserId, byte[] voiceBytes, String fileName,
                                    String caption, Integer playTimeMs, Integer sampleRate, long typingMs) {
        if (client == null || loginContext == null) {
            log.warn("Client is not ready, cannot send voice");
            return;
        }
        if (voiceBytes == null || voiceBytes.length == 0) {
            log.warn("Voice bytes are empty, skip sending");
            return;
        }
        try {
            if (caption != null && !caption.isBlank()) {
                client.sendTextWithTyping(targetUserId, caption, typingMs);
            }
            client.sendVoice(targetUserId, voiceBytes, fileName, playTimeMs, sampleRate);
            log.info("Voice sent with typing: to={}, fileName={}, size={}bytes, duration={}ms",
                    targetUserId, fileName, voiceBytes.length, playTimeMs);
        } catch (Exception e) {
            log.error("Send voice with typing failed: {}", resolveRootCauseMessage(e));
        }
    }

    public void sendVoiceReply(String targetUserId, String text) {
        sendVoiceReply(targetUserId, text, sessionManager.getVoicePreference(targetUserId));
    }

    private void sendVoiceReply(String targetUserId, String text, String requestedVoice) {
        if (client == null || loginContext == null) {
            log.warn("Client is not ready, cannot send voice reply");
            return;
        }
        if (text == null || text.isBlank()) {
            log.warn("Voice reply text is blank, skip sending");
            return;
        }
        try {
            List<SpeechSynthesisService.SynthesizedAudio> audios = speechSynthesisService.synthesize(text, requestedVoice);
            String actualVoice = requestedVoice == null || requestedVoice.isBlank() ? "default" : requestedVoice;
            String voiceFileLabel = actualVoice.replaceAll("[^A-Za-z0-9_-]", "_");
            for (int index = 0; index < audios.size(); index++) {
                SpeechSynthesisService.SynthesizedAudio audio = audios.get(index);
                String fileName = "voice-reply-" + voiceFileLabel + "-" + System.currentTimeMillis() + "-" + index + ".wav";
                client.sendFile(targetUserId, audio.audioBytes(), fileName, null);
                log.info("Voice reply file sent: to={}, voice={}, fileName={}, size={}bytes, duration={}ms",
                        targetUserId, actualVoice, fileName, audio.audioBytes().length, audio.durationMs());
            }
        } catch (Exception e) {
            log.error("Send voice reply file failed: {}", resolveRootCauseMessage(e), e);
            sendTextWithTyping(targetUserId, text, 800);
        }
    }

    private record VoiceReplyRequest(String text, String voice) {
    }

    public void sendVideo(String targetUserId, byte[] videoBytes, String fileName, Integer playLengthMs, String caption) {
        if (client == null || loginContext == null) {
            log.warn("Client is not ready, cannot send video");
            return;
        }
        if (videoBytes == null || videoBytes.length == 0) {
            log.warn("Video bytes are empty, skip sending");
            return;
        }
        try {
            client.sendVideo(targetUserId, videoBytes, fileName, playLengthMs, caption);
            log.info("Video sent: to={}, fileName={}, size={}bytes", targetUserId, fileName, videoBytes.length);
        } catch (Exception e) {
            log.error("Send video failed: {}", resolveRootCauseMessage(e));
        }
    }

    public boolean awaitLogin(long timeout, TimeUnit unit) throws InterruptedException {
        return loginLatch.await(timeout, unit);
    }

    public LoginContext getLoginContext() {
        return loginContext;
    }

    public String getBotId() {
        return loginContext != null ? loginContext.getBotId() : null;
    }

    public static String resolveRootCauseMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = cause.getClass().getSimpleName();
        }

        if (cause instanceof UnknownHostException) {
            return "DNS resolve failed. (" + msg + ")";
        }
        if (cause instanceof ConnectException) {
            return "Connection refused. (" + msg + ")";
        }
        if (cause instanceof java.net.SocketTimeoutException) {
            return "Connection timeout. (" + msg + ")";
        }
        if (cause instanceof javax.net.ssl.SSLException) {
            return "SSL/TLS handshake failed. (" + msg + ")";
        }
        return msg;
    }

    @PreDestroy
    public void destroy() {
        if (client != null) {
            client.close();
            log.info("iLink client closed");
        }
    }
}
