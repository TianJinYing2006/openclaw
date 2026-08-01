package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.orchestration.ToolRegistry;
import com.example.ykdsummer.ai.service.ImageTaskStatusStore;
import com.example.ykdsummer.ai.service.ImageTaskStatusStore.ImageTask;
import com.example.ykdsummer.ai.service.ImageTaskStatusStore.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全量真实 Tool 冒烟验收。只有显式设置 FULL_LIVE_TOOLS_TEST=true 才会联网、生成媒体或写入测试资产。
 * 每个已注册 Tool 都通过 ToolRegistry 调用一次；外部服务未配置时，正确返回友好提示也会记录下来。
 */
@SpringBootTest(properties = {"ilink.enabled=false", "app.ai.usage.enabled=false"})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "FULL_LIVE_TOOLS_TEST", matches = "true")
class FullLiveToolRegistrySmokeTest {

    private static final Pattern ASSET_ID = Pattern.compile("\\b(?:img|doc)_[a-z0-9]+\\b");
    private static final Pattern TASK_ID = Pattern.compile("任务编号 ([^，）\\s]+)");

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private ToolArtifactCollector artifacts;

    @Autowired
    private ImageTaskStatusStore imageTaskStore;

    private final String userId = "full-live-tool-" + UUID.randomUUID();
    private final Map<String, String> results = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();

    @AfterEach
    void clearArtifacts() {
        artifacts.discard();
    }

    @Test
    @Timeout(900)
    void invokesEveryRegisteredToolWithRepresentativeInputs() throws Exception {
        artifacts.begin(userId);
        Path tempDirectory = Files.createTempDirectory("full-live-tools-");
        Path png = writePng(tempDirectory.resolve("sample.png"));
        Path wav = writeToneWav(tempDirectory.resolve("sample.wav"));
        Path mp4 = writeVideo(tempDirectory.resolve("sample.mp4"));

        // 本地文档、资产和图片 Tool 需要先建立实际上下文，后续调用使用本轮返回的真实 assetId。
        String document = call("create_document", "全量验收文档", "txt", "第一版：真实 Tool 验收");
        String documentId = requireAssetId(document, "doc_");
        call("get_current_document");
        call("replace_document_content", documentId, "第二版：真实 Tool 验收完成", "txt", "更新验收结果");
        call("convert_document_format", documentId, "pdf");
        call("restore_document_version", documentId, 1);
        call("produce_file", "full-live-tool.json", "json", "{\"verification\":true}", "全量验收附件");

        String image = call("generate_image", "一张极简扁平插画：白色背景中央有一个红色正方形，不包含文字。");
        String imageTaskId = requireTaskId(image);
        ImageTask generatedTask = awaitImageTask(imageTaskId);
        assertThat(generatedTask.status()).isEqualTo(Status.SUCCEEDED);
        String imageId = generatedTask.resultAssetId();
        assertThat(imageId).startsWith("img_");
        call("get_current_image");
        call("list_recent_images");
        call("inspect_image", imageId, "画面中是什么颜色和形状？");
        String revision = call("create_image_revision", imageId, "保持白色背景与正方形构图不变，把红色正方形改成蓝色。");
        ImageTask revisionTask = awaitImageTask(requireTaskId(revision));
        assertThat(revisionTask.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(revisionTask.resultAssetId()).isEqualTo(imageId);
        call("restore_image_version", imageId, 1);
        call("get_running_tasks");
        call("check_image_task", imageTaskId == null ? "missing-task" : imageTaskId);
        call("retry_last_image_task");

        call("list_recent_assets", "最近生成的测试资产");
        call("select_asset", documentId, 1);
        call("describe_asset", documentId);
        call("resend_asset", documentId, 1);

        // 文件路径 Tool 使用本轮临时的有效媒体，不依赖微信入站消息。
        call("transcribe_audio", wav.toString());
        call("analyze_image", png.toString(), "这张图里有什么？");
        call("analyze_video", mp4.toString(), "概括视频画面。");

        // 已由独立实时探针验证的地图/博查也从 ToolRegistry 再走一次，覆盖回调注册和参数绑定。
        call("geoEncode", "北京天安门", "北京");
        call("routePlan", "116.397463,39.909187", "116.407387,39.904179", "driving", "北京故宫", "010", "010");
        call("search_web", "OpenAI 最新动态");
        call("web_search", "今天的科技新闻");
        call("fetch_web_page", "https://example.com");
        call("get_current_weather", "北京");
        call("convert_currency", "USD", "CNY", 1.0d);
        call("query_ip_location", "8.8.8.8");
        call("search_poi", "北京大学", "北京");
        call("search_nearby_poi", "餐厅", 39.908d, 116.397d, 5);
        call("geocode", "北京市海淀区中关村");
        call("get_phone_info", "13800138000");
        call("get_qq_user", "10000");
        call("get_steam_user", "76561197960287930");
        call("get_epic_free_games");
        call("query_today_in_history", 7, 24);
        call("get_horoscope", "白羊座", "today");
        call("search_recipe", "红烧肉", 3);
        call("get_recipe_detail", 1);
        call("search_scenic_spot", "北京", false, 3);
        call("query_express_tracking", "SF", "SF1234567890", "");
        call("calculate_bfr", 70, 175, 30, 1);

        // TTS、飞书在当前配置中可能未启用；仍要验证每个 Tool 能返回可读错误，而不是抛出异常。
        call("list_voice_options");
        call("get_current_voice");
        call("set_voice", "longanyang");
        call("reset_voice");
        call("synthesize_speech", "这是全量 Tool 验收语音。");
        call("text_to_speech", "这是全量 Tool 验收语音。", "longanyang");
        call("feishu_doc_search", "全量验收");
        call("feishu_doc_read", "doc_missing");
        call("feishu_doc_create", "全量验收", "测试内容");
        call("feishu_doc_append", "doc_missing", "测试内容");
        call("feishu_doc_update", "doc_missing", "测试内容");
        call("feishu_drive_file_search", "全量验收", 10, "");
        call("feishu_message_send", "ou_missing", "open_id", "全量验收消息", "text");
        call("feishu_user_search", "测试", 10);
        call("feishu_calendar_list_events", "primary", 10, "", "", "");
        call("feishu_calendar_create_event", "primary", "全量验收", "2026-07-24T15:00:00+08:00", "2026-07-24T15:30:00+08:00", "", "");
        call("feishu_bitable_query", "app_missing", "tbl_missing", 10, "");
        call("feishu_bitable_add_record", "app_missing", "tbl_missing", "{\"名称\":\"全量验收\"}");
        call("feishu_bitable_update_record", "app_missing", "tbl_missing", "rec_missing", "{\"名称\":\"全量验收\"}");
        call("feishu_approval_list", "approval_missing", "PENDING", 10, "");
        call("feishu_approval_approve", "approval_missing", "instance_missing", "task_missing", "同意", "");
        call("feishu_approval_reject", "approval_missing", "instance_missing", "task_missing", "拒绝", "");

        call("clear_current_memory");
        assertThat(results.keySet()).containsExactlyInAnyOrderElementsOf(
                registry.allToolMeta().stream().map(ToolRegistry.ToolMeta::name).toList());
        assertThat(failures).as("Tool invocation errors").isEmpty();
        assertThat(artifacts.finish()).isNotEmpty();
    }

    private String call(String toolName, Object... arguments) {
        ToolRegistry.ToolEntry entry = registry.find(toolName)
                .orElseThrow(() -> new AssertionError("未注册 Tool：" + toolName));
        try {
            Object response = entry.method().invoke(entry.bean(), arguments);
            String text = String.valueOf(response);
            if (text.isBlank() || "null".equals(text)) {
                failures.add(toolName + " returned blank result");
            }
            results.put(toolName, text);
            System.out.printf("FULL_LIVE_TOOL name=%s result=%s%n", toolName, summarize(text));
            return text;
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getTargetException();
            failures.add(toolName + ": " + cause.getClass().getSimpleName() + ": " + safeMessage(cause));
            results.put(toolName, "THREW " + cause.getClass().getSimpleName());
            System.out.printf("FULL_LIVE_TOOL name=%s threw=%s%n", toolName, cause.getClass().getSimpleName());
            return "";
        } catch (ReflectiveOperationException | RuntimeException exception) {
            failures.add(toolName + ": " + exception.getClass().getSimpleName() + ": " + safeMessage(exception));
            results.put(toolName, "THREW " + exception.getClass().getSimpleName());
            System.out.printf("FULL_LIVE_TOOL name=%s threw=%s%n", toolName, exception.getClass().getSimpleName());
            return "";
        }
    }

    private static String requireAssetId(String result, String prefix) {
        String assetId = firstAssetId(result, prefix);
        assertThat(assetId).as("expected %s in %s", prefix, summarize(result)).isNotNull();
        return assetId;
    }

    private static String firstAssetId(String result, String prefix) {
        Matcher matcher = ASSET_ID.matcher(result == null ? "" : result);
        while (matcher.find()) {
            String value = matcher.group();
            if (value.startsWith(prefix)) {
                return value;
            }
        }
        return null;
    }

    private static String firstTaskId(String result) {
        Matcher matcher = TASK_ID.matcher(result == null ? "" : result);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String requireTaskId(String result) {
        String taskId = firstTaskId(result);
        assertThat(taskId).as("expected task ID in %s", summarize(result)).isNotBlank();
        return taskId;
    }

    private ImageTask awaitImageTask(String taskId) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(7);
        while (System.nanoTime() < deadline) {
            ImageTask task = imageTaskStore.find(userId, taskId).orElse(null);
            if (task != null && task.status() != Status.RUNNING) {
                return task;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("图片任务等待超时：" + taskId);
    }

    private static Path writePng(Path target) throws Exception {
        BufferedImage image = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 96, 96);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(24, 24, 48, 48);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", target.toFile());
        return target;
    }

    private static Path writeToneWav(Path target) throws Exception {
        int sampleRate = 16_000;
        ByteBuffer wav = ByteBuffer.allocate(44 + sampleRate * 2).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + sampleRate * 2).put(new byte[]{'W', 'A', 'V', 'E'});
        wav.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) 1);
        wav.putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        wav.put(new byte[]{'d', 'a', 't', 'a'}).putInt(sampleRate * 2);
        for (int index = 0; index < sampleRate; index++) {
            wav.putShort((short) (Math.sin(2.0 * Math.PI * 440.0 * index / sampleRate) * 4_000));
        }
        Files.write(target, wav.array());
        return target;
    }

    private static Path writeVideo(Path target) throws Exception {
        Process process = new ProcessBuilder(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=c=blue:s=320x240:d=3",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
                "-c:v", "mpeg4", "-c:a", "aac", "-shortest", target.toString()
        ).start();
        assertThat(process.waitFor()).as("could not create video fixture").isZero();
        return target;
    }

    private static String summarize(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 300 ? compact : compact.substring(0, 300) + "...";
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? "no message" : summarize(message);
    }
}
