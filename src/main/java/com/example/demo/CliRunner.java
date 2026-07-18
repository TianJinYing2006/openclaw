package com.example.demo;

import com.example.demo.control.CommandManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * CLI 交互循环 — 启动后接管控制台，处理 exit/空输入/编码探测
 */
@Component
public class CliRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);

    @Autowired
    @Lazy
    private CommandManager commandManager;

    @Override
    public void run(String... args) throws Exception {
        Charset charset = detectCharset();
        log.info("CLI 交互循环启动，编码: {}", charset.displayName());

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, charset))) {
            while (true) {
                System.out.print("> ");
                String line = reader.readLine();

                // EOF (Ctrl+Z / Ctrl+D)
                if (line == null) {
                    System.out.println("exit");
                    break;
                }

                // 空输入跳过
                if (line.isBlank()) {
                    continue;
                }

                String trimmed = line.trim();

                // exit 退出
                if ("exit".equalsIgnoreCase(trimmed) || "/exit".equalsIgnoreCase(trimmed)) {
                    System.out.println("Bye!");
                    break;
                }

                // 分发命令
                String result = commandManager.dispatch(trimmed);
                if (result != null) {
                    System.out.println(result);
                } else {
                    System.out.println("未知命令，输入 /help 查看可用命令");
                }
            }
        }

        log.info("CLI 交互循环退出");
        System.exit(0);
    }

    /**
     * 探测控制台编码：Windows 中文环境通常为 GBK，其他环境为 UTF-8
     */
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
            // Windows 默认 GBK
            try {
                return Charset.forName("GBK");
            } catch (Exception ignored) {
            }
        }
        return StandardCharsets.UTF_8;
    }
}
