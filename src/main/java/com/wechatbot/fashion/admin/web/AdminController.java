package com.wechatbot.fashion.admin.web;

import com.wechatbot.fashion.admin.ilink.ManagedBotInstanceManager;
import com.wechatbot.fashion.admin.service.AdminPlatformService;
import com.wechatbot.fashion.bot.runtime.ILinkRuntimeState;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Server-rendered local administrator UI. No platform user can access this site. */
@Controller
@RequestMapping("/admin")
@ConditionalOnProperty(prefix = "app.admin", name = "enabled", havingValue = "true")
public class AdminController {
    private final AdminPlatformService platform;
    private final ManagedBotInstanceManager instances;

    public AdminController(AdminPlatformService platform, ManagedBotInstanceManager instances) {
        this.platform = platform;
        this.instances = instances;
    }

    @GetMapping("/login")
    public String login(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated() ? "redirect:/admin" : "admin/login";
    }

    @GetMapping
    public String dashboard(Model model) {
        AdminPlatformService.DashboardSnapshot snapshot = platform.dashboardSnapshot();
        AdminPlatformService.Dashboard dashboard = snapshot.dashboard();
        List<RuntimeInstance> runtimeInstances = snapshot.instances().stream()
                .map(instance -> RuntimeInstance.from(instance, instances.status(instance.instanceId())))
                .toList();
        long polling = runtimeInstances.stream().filter(RuntimeInstance::polling).count();
        long waitingForQr = runtimeInstances.stream().filter(value -> "WAITING_FOR_QR_SCAN".equals(value.liveStatus())).count();
        long errors = runtimeInstances.stream().filter(RuntimeInstance::hasError).count();
        long outOfSync = runtimeInstances.stream().filter(instance -> !instance.stateSynchronized()).count();
        model.addAttribute("dashboard", dashboard);
        model.addAttribute("runtimeInstances", runtimeInstances);
        model.addAttribute("runtimeSummary", new RuntimeSummary(polling, waitingForQr, errors, outOfSync));
        model.addAttribute("dashboardCapturedAt", snapshot.capturedAt());
        model.addAttribute("usageWindowStart", snapshot.usageWindowStart());
        model.addAttribute("activePage", "dashboard");
        return "admin/dashboard";
    }

    @GetMapping("/users")
    public String users(Model model) {
        List<UserListRow> users = platform.listUsers().stream()
                .map(user -> UserListRow.from(user, user.instanceId() == null ? null : instances.status(user.instanceId())))
                .toList();
        long activeBindings = users.stream().filter(UserListRow::hasActiveInstance).count();
        long connected = users.stream().filter(user -> "CONNECTED".equals(user.instance().liveStatus())).count();
        long waitingForQr = users.stream().filter(user -> "WAITING_FOR_QR_SCAN".equals(user.instance().liveStatus())
                || "PENDING_QR".equals(user.instance().persistedStatus())).count();
        long outOfSync = users.stream().filter(user -> user.hasActiveInstance() && !user.instance().stateSynchronized()).count();
        model.addAttribute("users", users);
        model.addAttribute("userSummary", new UserSummary(users.size(), activeBindings, connected, waitingForQr, outOfSync));
        model.addAttribute("activePage", "users");
        return "admin/users";
    }

    @GetMapping("/chat-users")
    public String chatUsers(Model model) {
        List<AdminPlatformService.ChatUserOverview> chatUsers = platform.chatUsers();
        Instant activeSince = Instant.now().minus(Duration.ofHours(24));
        long currentInstances = chatUsers.stream().filter(value -> !value.archivedInstance()).count();
        long historicalInstances = chatUsers.size() - currentInstances;
        long activeLast24Hours = chatUsers.stream().filter(value -> value.lastSeenAt() != null
                && !value.lastSeenAt().isBefore(activeSince)).count();
        long modelFailures = chatUsers.stream().mapToLong(AdminPlatformService.ChatUserOverview::modelFailures).sum();
        long toolFailures = chatUsers.stream().mapToLong(AdminPlatformService.ChatUserOverview::toolFailures).sum();
        model.addAttribute("chatUsers", chatUsers);
        model.addAttribute("chatUserSummary", new ChatUserSummary(chatUsers.size(), currentInstances, historicalInstances,
                activeLast24Hours, modelFailures, toolFailures));
        model.addAttribute("activePage", "chat-users");
        return "admin/chat-users";
    }

    @GetMapping("/chat-users/{appUserId}")
    public String chatUserDetail(@PathVariable long appUserId, Model model, RedirectAttributes redirect) {
        Optional<AdminPlatformService.ChatUserOverview> user = platform.findChatUser(appUserId);
        if (user.isEmpty()) {
            redirect.addFlashAttribute("error", "未找到该聊天用户");
            return "redirect:/admin/chat-users";
        }
        model.addAttribute("chatUser", user.get());
        model.addAttribute("modelCalls", platform.modelInvocationsForChatUser(appUserId));
        model.addAttribute("messages", platform.conversationEntriesForChatUser(appUserId));
        model.addAttribute("tasks", platform.tasksForChatUser(appUserId));
        model.addAttribute("assets", platform.assetsForChatUser(appUserId));
        model.addAttribute("toolUsage", platform.toolUsageForChatUser(appUserId));
        model.addAttribute("activePage", "chat-users");
        return "admin/chat-user-detail";
    }

    @PostMapping("/users")
    public String createUser(@RequestParam String username, @RequestParam(required = false) String remark,
                             RedirectAttributes redirect) {
        try {
            AdminPlatformService.UserOverview user = platform.createUser(username, remark);
            redirect.addFlashAttribute("notice", "已创建用户和独立机器人实例，请生成二维码完成绑定。");
            return "redirect:/admin/users/" + user.id();
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", safeMessage(exception));
            return "redirect:/admin/users";
        }
    }

    @GetMapping("/users/{userId}")
    public String userDetail(@PathVariable long userId, Model model, RedirectAttributes redirect) {
        Optional<AdminPlatformService.UserOverview> user = platform.findUser(userId);
        if (user.isEmpty()) {
            redirect.addFlashAttribute("error", "未找到该平台用户");
            return "redirect:/admin/users";
        }
        AdminPlatformService.UserOverview overview = user.get();
        ILinkRuntimeState.Snapshot runtime = overview.instanceId() == null ? null : instances.status(overview.instanceId());
        model.addAttribute("user", overview);
        model.addAttribute("runtime", runtime);
        model.addAttribute("currentInstance", UserInstanceView.from(overview, runtime));
        model.addAttribute("qrCodeUrl", overview.instanceId() == null ? "" : instances.qrCodeUrl(overview.instanceId()));
        model.addAttribute("archived", platform.archivedInstances(userId));
        model.addAttribute("messages", overview.instanceId() == null ? java.util.List.of() : platform.conversationEntries(overview.instanceId()));
        model.addAttribute("tasks", overview.instanceId() == null ? java.util.List.of() : platform.tasks(overview.instanceId()));
        model.addAttribute("assets", overview.instanceId() == null ? java.util.List.of() : platform.assets(overview.instanceId()));
        model.addAttribute("usage", overview.instanceId() == null ? AdminPlatformService.UsageTotals.empty() : platform.usageSummary(overview.instanceId()));
        model.addAttribute("toolUsage", overview.instanceId() == null ? java.util.List.of() : platform.toolUsage(overview.instanceId()));
        model.addAttribute("modelCalls", overview.instanceId() == null ? java.util.List.of() : platform.modelInvocationsForInstance(overview.instanceId()));
        model.addAttribute("events", platform.eventsForUser(userId));
        model.addAttribute("activePage", "users");
        return "admin/user-detail";
    }

    @PostMapping("/users/{userId}/qr")
    public String requestQr(@PathVariable long userId, RedirectAttributes redirect) {
        try {
            instances.requestQr(userId);
            redirect.addFlashAttribute("notice", "正在向微信申请二维码，页面会在二维码返回后自动显示。二维码失效后可再次生成。");
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", safeMessage(exception));
        }
        return "redirect:/admin/users/" + userId;
    }

    @GetMapping(value = "/users/{userId}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    @ResponseBody
    public ResponseEntity<byte[]> qrCodeImage(@PathVariable long userId) {
        AdminPlatformService.UserOverview user = platform.findUser(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Platform user was not found"));
        if (user.instanceId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No active bot instance");
        }
        String content = instances.qrCodeUrl(user.instanceId());
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "QR code is not ready");
        }
        try {
            var matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 300, 300,
                    Map.of(EncodeHintType.MARGIN, 1, EncodeHintType.CHARACTER_SET, "UTF-8"));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.IMAGE_PNG)
                    .body(output.toByteArray());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not create QR image", exception);
        }
    }

    @PostMapping("/users/{userId}/archive")
    public String archive(@PathVariable long userId, @RequestParam(required = false) String reason,
                          RedirectAttributes redirect) {
        try {
            AdminPlatformService.UserOverview user = platform.findUser(userId).orElseThrow();
            if (user.instanceId() != null) instances.stop(user.instanceId());
            platform.archiveActiveInstance(userId, reason);
            redirect.addFlashAttribute("notice", "已解绑并归档。聊天、资产、用量和会话加密快照已保留，可从下方回收记录恢复。");
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", safeMessage(exception));
        }
        return "redirect:/admin/users/" + userId;
    }

    @PostMapping("/users/{userId}/restore/{instanceId}")
    public String restore(@PathVariable long userId, @PathVariable String instanceId, RedirectAttributes redirect) {
        try {
            platform.findUser(userId).map(AdminPlatformService.UserOverview::instanceId).ifPresent(instances::stop);
            platform.restoreArchivedInstance(userId, instanceId);
            instances.resume(instanceId);
            redirect.addFlashAttribute("notice", "已恢复历史机器人实例。若登录态失效，页面会显示新的二维码。");
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", safeMessage(exception));
        }
        return "redirect:/admin/users/" + userId;
    }

    @PostMapping("/users/{userId}/purge/{instanceId}")
    public String purge(@PathVariable long userId, @PathVariable String instanceId,
                        @RequestParam String confirmation, @RequestParam String reason, RedirectAttributes redirect) {
        try {
            instances.stop(instanceId);
            platform.permanentlyDeleteArchivedInstance(userId, instanceId, confirmation, reason);
            redirect.addFlashAttribute("notice", "归档实例已永久删除，操作已写入审计日志且不可恢复。");
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", safeMessage(exception));
        }
        return "redirect:/admin/users/" + userId;
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "操作失败，请查看服务日志" : message;
    }

    /** Combines durable instance metadata with counters that intentionally live only for this JVM process. */
    public record RuntimeInstance(
            String instanceId,
            long platformUserId,
            String username,
            String persistedStatus,
            Instant persistedUpdatedAt,
            String liveStatus,
            boolean polling,
            boolean stateSynchronized,
            String stateNote,
            long receivedMessages,
            long sentMessages,
            String lastMessageType,
            java.time.Instant lastMessageAt,
            String lastError,
            long modelRequestsToday,
            long totalTokensToday,
            long imageOperationsToday,
            long ttsOperationsToday,
            long toolCallsToday,
            long toolFailuresToday
    ) {
        static RuntimeInstance from(AdminPlatformService.DashboardInstance instance, ILinkRuntimeState.Snapshot runtime) {
            String persisted = normalizedStatus(instance.persistedStatus(), "UNKNOWN");
            String status = runtime == null ? "NOT_RUNNING" : normalizedStatus(runtime.connectionStatus(), "UNKNOWN");
            String error = runtime != null && runtime.lastError() != null && !runtime.lastError().isBlank()
                    ? runtime.lastError() : instance.persistedError();
            StateConsistency consistency = consistency(persisted, status, runtime);
            return new RuntimeInstance(instance.instanceId(), instance.platformUserId(), instance.username(), persisted,
                    instance.persistedUpdatedAt(), status, runtime != null && runtime.polling(), consistency.synchronizedState(),
                    consistency.note(), runtime == null ? 0 : runtime.receivedMessages(),
                    runtime == null ? 0 : runtime.sentMessages(), runtime == null ? "" : runtime.lastMessageType(),
                    runtime == null ? null : runtime.lastMessageAt(), error == null ? "" : error,
                    instance.modelRequestsToday(), instance.totalTokensToday(), instance.imageOperationsToday(),
                    instance.ttsOperationsToday(), instance.toolCallsToday(), instance.toolFailuresToday());
        }

        boolean hasError() {
            return "ERROR".equals(liveStatus) || "ERROR".equals(persistedStatus) || !lastError.isBlank();
        }

        public String liveTone() {
            return statusTone(liveStatus);
        }

        public String persistedTone() {
            return statusTone(persistedStatus);
        }

        private static StateConsistency consistency(String persisted, String live, ILinkRuntimeState.Snapshot runtime) {
            if (runtime == null) return new StateConsistency(false, "当前进程未接管");
            if (persisted.equals(live)) return new StateConsistency(true, "已同步");
            if ("STARTING".equals(live) || "WAITING_FOR_QR_SCAN".equals(live)) {
                return new StateConsistency(false, "状态切换中");
            }
            return new StateConsistency(false, "待同步");
        }

        private static String normalizedStatus(String status, String fallback) {
            return status == null || status.isBlank() ? fallback : status;
        }

        private static String statusTone(String status) {
            return switch (status) {
                case "CONNECTED" -> "good";
                case "WAITING_FOR_QR_SCAN", "STARTING", "PENDING_QR" -> "warning";
                case "ERROR" -> "error";
                default -> "neutral";
            };
        }

        private record StateConsistency(boolean synchronizedState, String note) { }
    }

    public record RuntimeSummary(long pollingInstances, long waitingForQr, long instancesWithErrors,
                                 long instancesOutOfSync) { }

    /** Durable user/instance fields paired with the short-lived state kept by the current JVM. */
    public record UserInstanceView(
            String instanceId,
            int generation,
            String lifecycleState,
            String persistedStatus,
            Instant persistedUpdatedAt,
            String liveStatus,
            boolean polling,
            boolean stateSynchronized,
            String stateNote,
            String ilinkAccountId,
            long receivedMessages,
            long sentMessages,
            String lastMessageType,
            Instant lastMessageAt,
            String lastError
    ) {
        static UserInstanceView from(AdminPlatformService.UserOverview user, ILinkRuntimeState.Snapshot runtime) {
            if (user.instanceId() == null || user.instanceId().isBlank()) {
                return new UserInstanceView("", 0, "UNBOUND", "UNBOUND", user.instanceUpdatedAt(), "NOT_RUNNING",
                        false, true, "未绑定实例", "", 0, 0, "", null, "");
            }
            String persisted = normalizedStatus(user.connectionStatus(), "UNKNOWN");
            String live = runtime == null ? "NOT_RUNNING" : normalizedStatus(runtime.connectionStatus(), "UNKNOWN");
            String error = runtime != null && runtime.lastError() != null && !runtime.lastError().isBlank()
                    ? runtime.lastError() : user.lastError();
            StateConsistency consistency = consistency(persisted, live, runtime);
            return new UserInstanceView(user.instanceId(), user.generation(), normalizedStatus(user.lifecycleState(), "UNKNOWN"),
                    persisted, user.instanceUpdatedAt(), live, runtime != null && runtime.polling(),
                    consistency.synchronizedState(), consistency.note(), safe(user.ilinkAccountId()),
                    runtime == null ? 0 : runtime.receivedMessages(), runtime == null ? 0 : runtime.sentMessages(),
                    runtime == null ? "" : safe(runtime.lastMessageType()), runtime == null ? null : runtime.lastMessageAt(),
                    safe(error));
        }

        public boolean hasActiveInstance() { return !instanceId.isBlank(); }
        public boolean canRequestQr() { return hasActiveInstance() && !"CONNECTED".equals(liveStatus); }
        public String liveTone() { return statusTone(liveStatus); }
        public String persistedTone() { return statusTone(persistedStatus); }
        public String lifecycleTone() { return switch (lifecycleState) {
            case "ACTIVE" -> "good";
            case "PENDING_QR" -> "warning";
            case "ARCHIVED" -> "neutral";
            default -> "neutral";
        }; }
        public String liveLabel() { return connectionStatusLabel(liveStatus); }
        public String persistedLabel() { return connectionStatusLabel(persistedStatus); }
        public String lifecycleLabel() { return switch (lifecycleState) {
            case "ACTIVE" -> "已启用";
            case "PENDING_QR" -> "待绑定";
            case "ARCHIVED" -> "已归档";
            case "UNBOUND" -> "无当前实例";
            default -> lifecycleState;
        }; }
        public String instanceShortId() { return instanceId.length() <= 12 ? instanceId : instanceId.substring(0, 8) + "..."; }
        public String accountState() { return ilinkAccountId.isBlank() ? "未绑定" : "已绑定"; }
        public String lastMessageLabel() { return lastMessageType.isBlank() ? "暂无" : lastMessageType; }

        private static StateConsistency consistency(String persisted, String live, ILinkRuntimeState.Snapshot runtime) {
            if (runtime == null) return new StateConsistency(false, "当前进程未接管");
            if (persisted.equals(live)) return new StateConsistency(true, "已同步");
            if ("STARTING".equals(live) || "WAITING_FOR_QR_SCAN".equals(live)) {
                return new StateConsistency(false, "状态切换中");
            }
            return new StateConsistency(false, "待同步");
        }

        private static String statusTone(String status) {
            return switch (status) {
                case "CONNECTED" -> "good";
                case "WAITING_FOR_QR_SCAN", "STARTING", "PENDING_QR" -> "warning";
                case "ERROR" -> "error";
                default -> "neutral";
            };
        }

        private static String connectionStatusLabel(String status) {
            return switch (status) {
                case "CONNECTED" -> "已连接";
                case "WAITING_FOR_QR_SCAN" -> "等待扫码";
                case "STARTING" -> "启动中";
                case "PENDING_QR" -> "待生成二维码";
                case "ERROR" -> "异常";
                case "NOT_RUNNING" -> "未在当前进程";
                case "UNBOUND" -> "未绑定";
                default -> status;
            };
        }

        private static String normalizedStatus(String status, String fallback) {
            return status == null || status.isBlank() ? fallback : status;
        }

        private static String safe(String value) { return value == null ? "" : value; }

        private record StateConsistency(boolean synchronizedState, String note) { }
    }

    public record UserListRow(long id, String username, String remark, String platformStatus, UserInstanceView instance) {
        static UserListRow from(AdminPlatformService.UserOverview user, ILinkRuntimeState.Snapshot runtime) {
            return new UserListRow(user.id(), user.username(), user.remark(), user.status(), UserInstanceView.from(user, runtime));
        }

        public boolean hasActiveInstance() { return instance.hasActiveInstance(); }
    }

    public record UserSummary(long totalUsers, long activeBindings, long connectedInstances, long waitingForQr,
                              long instancesOutOfSync) { }

    public record ChatUserSummary(long totalIdentities, long currentInstanceIdentities, long historicalInstanceIdentities,
                                  long activeLast24Hours, long modelFailures, long toolFailures) { }
}
