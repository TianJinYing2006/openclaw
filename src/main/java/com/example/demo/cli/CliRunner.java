package com.example.demo.cli;

import com.example.demo.ai.SpeechRecognitionService;
import com.example.demo.chat.CommandHandler;
import com.example.demo.chat.CommandManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@Profile("!test")
public class CliRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);
    private static final String ASR_PREFIX = "/asr ";
    private static final String ASR_DISPATCH_PREFIX = "/asr-chat ";

    @Autowired
    @Lazy
    private CommandManager commandManager;

    @Autowired
    @Lazy
    private CommandHandler commandHandler;

    @Autowired
    private SpeechRecognitionService speechRecognitionService;

    @Override
    public void run(String... args) throws Exception {
        Charset charset = detectCharset();
        log.info("CLI 交互循环启动，编码 {}", charset.displayName());
        System.out.println("语音识别命令：/asr <音频文件路径>，识别后对话：/asr-chat <音频文件路径>");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, charset))) {
            while (true) {
                System.out.print("> ");
                String line = reader.readLine();

                if (line == null) {
                    log.info("CLI 标准输入已关闭，保留 Web 和微信服务继续运行");
                    return;
                }
                if (line.isBlank()) {
                    continue;
                }

                String trimmed = line.trim();
                if ("exit".equalsIgnoreCase(trimmed) || "/exit".equalsIgnoreCase(trimmed)) {
                    System.out.println("Bye!");
                    log.info("收到 CLI 退出命令，关闭应用");
                    System.exit(0);
                    return;
                }

                if (trimmed.startsWith(ASR_DISPATCH_PREFIX)) {
                    transcribeFromCli(trimmed.substring(ASR_DISPATCH_PREFIX.length()).trim(), true);
                    continue;
                }
                if (trimmed.startsWith(ASR_PREFIX)) {
                    transcribeFromCli(trimmed.substring(ASR_PREFIX.length()).trim(), false);
                    continue;
                }

                String result = commandManager.dispatch(trimmed);
                if (result != null) {
                    System.out.println(result);
                } else {
                    System.out.println("未知命令，输入 /help 查看可用命令");
                }
            }
        }
    }

    private void transcribeFromCli(String pathText, boolean dispatch) {
        try {
            if (pathText.isBlank()) {
                System.out.println("用法：/asr <音频文件路径> 或 /asr-chat <音频文件路径>");
                return;
            }
            Path audioPath = Path.of(pathText).toAbsolutePath().normalize();
            if (!Files.isRegularFile(audioPath)) {
                System.out.println("音频文件不存在：" + audioPath);
                return;
            }

            byte[] audioBytes = Files.readAllBytes(audioPath);
            String transcript = speechRecognitionService.transcribe(audioBytes, detectAudioMimeType(audioPath, audioBytes));
            System.out.println("识别结果：" + transcript);
            if (dispatch) {
                String reply = commandHandler.handle(transcript, "cli-user");
                System.out.println("AI 回复：" + reply);
            }
        } catch (IOException e) {
            System.out.println("语音识别失败：请检查音频格式、网络、API Key 或配额");
            log.error("CLI 语音识别失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("语音识别请求被中断，请稍后重试");
        } catch (Exception e) {
            System.out.println("语音识别失败：" + e.getMessage());
            log.error("CLI 语音识别异常", e);
        }
    }

    private String detectAudioMimeType(Path path, byte[] bytes) {
        String fileName = path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".wav")) {
            return "audio/wav";
        }
        if (fileName.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (fileName.endsWith(".ogg") || fileName.endsWith(".opus")) {
            return "audio/ogg";
        }
        if (fileName.endsWith(".amr")) {
            return "audio/amr";
        }
        if (bytes.length >= 4 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return "audio/wav";
        }
        if (bytes.length >= 4 && bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S') {
            return "audio/ogg";
        }
        return "audio/opus";
    }

    private Charset detectCharset() {
        String encoding = System.getProperty("sun.stdout.encoding");
        if (encoding != null && !encoding.isEmpty()) {
            try {
                return Charset.forName(encoding);
            } catch (Exception ignored) {
            }
        }
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("windows")) {
            String consoleCp = System.getProperty("console.encoding");
            if (consoleCp != null && !consoleCp.isEmpty()) {
                try {
                    return Charset.forName(consoleCp);
                } catch (Exception ignored) {
                }
            }
            try {
                return Charset.forName("GBK");
            } catch (Exception ignored) {
            }
        }
        return StandardCharsets.UTF_8;
    }
}
