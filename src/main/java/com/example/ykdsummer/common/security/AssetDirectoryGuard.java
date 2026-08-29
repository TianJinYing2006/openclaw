package com.example.ykdsummer.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 敏感本地目录的权限收紧（磁盘侧保护）。
 *
 * <p>覆盖登录会话、图片资产、审计日志等含用户隐私的目录：
 * <ul>
 *   <li>Windows：{@code icacls /inheritance:r /grant:r 当前用户:(OI)(CI)F}，去掉继承并只给当前用户完全控制；</li>
 *   <li>POSIX：递归设置为 {@code rwx------}（目录）/ {@code rw-------}（文件）。</li>
 * </ul>
 * 防止局域网共享、备份或同机其它账号读取明文隐私文件。失败只告警，不阻断启动。</p>
 */
@Component
public class AssetDirectoryGuard {

    private static final Logger log = LoggerFactory.getLogger(AssetDirectoryGuard.class);

    private static final String[] PROTECTED_DIRS = {
            ".ai-assets",
            ".ai-assets/images",
            ".ai-assets/delivery",
            ".documents",
            ".ilink"
    };

    @EventListener(ApplicationReadyEvent.class)
    public void protectOnStartup() {
        for (String dir : PROTECTED_DIRS) {
            Path path = Path.of(dir).toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                continue;
            }
            try {
                restrict(path);
            } catch (Exception exception) {
                log.warn("Could not restrict directory permissions for {}: {}", path, exception.toString());
            }
        }
    }

    private static void restrict(Path path) throws IOException {
        if (isWindows()) {
            restrictWindows(path);
        } else {
            restrictPosix(path);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void restrictWindows(Path path) {
        String user = System.getProperty("user.name");
        if (user == null || user.isBlank()) {
            log.warn("Cannot restrict {}: system property user.name is empty", path);
            return;
        }
        try {
            Process process = new ProcessBuilder(
                    "icacls", path.toString(),
                    "/inheritance:r",
                    "/grant:r", user + ":(OI)(CI)F")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = process.waitFor(30, TimeUnit.SECONDS);
            int exit = done ? process.exitValue() : -1;
            if (exit != 0) {
                log.warn("icacls {} exit={}: {}", path, exit, output.strip());
            } else {
                log.info("Restricted directory ACL (Windows): {}", path);
            }
        } catch (Exception exception) {
            log.warn("icacls failed for {}: {}", path, exception.toString());
        }
    }

    private static void restrictPosix(Path path) throws IOException {
        Files.walk(path).forEach(current -> {
            try {
                boolean dir = Files.isDirectory(current);
                Files.setPosixFilePermissions(current,
                        PosixFilePermissions.fromString(dir ? "rwx------" : "rw-------"));
            } catch (IOException exception) {
                log.warn("Could not restrict {}: {}", current, exception.toString());
            }
        });
        log.info("Restricted directory permissions (POSIX): {}", path);
    }
}
