package com.wechatbot.fashion.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 从 Langfuse 查询 API 拉取 GENERATION observation，聚合成管理后台的用量报表。
 *
 * <p>四个维度：按模型拆分、按用户排名（join trace 的 userId / langfuse.user.id）、历史趋势（按天）、
 * 估算成本（单价表 × token）。数据经 5 分钟缓存，避免每次刷新都打 Langfuse API 触发限流。</p>
 *
 * <p>实现要点：token 计数只存在于 v1 {@code /api/public/observations}（v2 list 不带 usage 字段），
 * 故 generations 用 v1；traces 同样用 v1 做 userId join。两者都带 429 重试，单窗口数据量很小
 * （通常个位数~几百条），分页请求数远低于限流阈值。</p>
 */
@Service
public class LangfuseUsageService {
    private static final Logger log = LoggerFactory.getLogger(LangfuseUsageService.class);
    private static final ZoneId BJ = ZoneId.of("Asia/Shanghai");
    private static final int MAX_PAGES = 60; // 每维度最多翻 60 页，够管理报表用
    private static final Pattern RETRY_AFTER = Pattern.compile("\"retryAfterSeconds\"\\s*:\\s*(\\d+)");

    private final String baseUrl;
    private final String authHeader;
    private final Map<String, ModelPrice> priceMap;
    private final RestClient rest;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Cache<Integer, UsageReport> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).build();
    /** 限流冷却：429 后 30 秒内不再打 Langfuse，避免刷新风暴把预算打满。 */
    private final Cache<Integer, UsageReport> rlCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(30)).build();

    public LangfuseUsageService(
            @Value("${langfuse.query.base-url:}") String baseUrl,
            @Value("${langfuse.query.public-key:}") String publicKey,
            @Value("${langfuse.query.secret-key:}") String secretKey,
            @Value("${langfuse.query.price.qwen3.7-flash.input:0.0004}") double flashIn,
            @Value("${langfuse.query.price.qwen3.7-flash.output:0.0012}") double flashOut,
            @Value("${langfuse.query.price.qwen3.7-max.input:0.002}") double maxIn,
            @Value("${langfuse.query.price.qwen3.7-max.output:0.006}") double maxOut) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/$", "");
        boolean ok = !this.baseUrl.isBlank() && publicKey != null && !publicKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
        this.authHeader = ok ? "Basic " + java.util.Base64.getEncoder()
                .encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8)) : null;
        this.rest = RestClient.builder()
                .defaultHeader("Authorization", authHeader == null ? "" : authHeader)
                .defaultHeader("Accept", "application/json")
                .build();
        this.priceMap = new LinkedHashMap<>();
        this.priceMap.put("qwen3.7-flash", new ModelPrice(flashIn, flashOut));
        this.priceMap.put("qwen3.7-max", new ModelPrice(maxIn, maxOut));
    }

    public boolean isConfigured() { return authHeader != null; }

    public UsageReport report(Duration window) {
        if (authHeader == null) {
            return UsageReport.unconfigured("未配置 Langfuse 查询密钥（请在环境变量设置 LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY）");
        }
        int days = (int) Math.max(1, window.toDays());
        UsageReport cached = cache.getIfPresent(days);
        if (cached != null) return cached;
        UsageReport cooldown = rlCache.getIfPresent(days); // 限流冷却中，直接返回友好提示，不再打 Langfuse
        if (cooldown != null) return cooldown;
        Instant to = Instant.now();
        Instant from = to.minus(window);
        try {
            List<Gen> gens = fetchGenerations(from, to);
            Map<String, String> traceUsers = fetchTraceUsers(from, to);
            UsageReport r = aggregate(gens, traceUsers);
            cache.put(days, r);
            return r;
        } catch (RateLimitedException e) {
            int wait = e.retryAfter > 0 ? e.retryAfter : 60;
            log.warn("Langfuse 429，进入 30s 冷却，建议 {}s 后刷新", wait);
            UsageReport err = UsageReport.error("Langfuse 当前限流（429），请约 " + wait
                    + " 秒后刷新页面。用量数据每 5 分钟缓存一次，冷却期内刷新不会重复请求。");
            rlCache.put(days, err);
            return err;
        } catch (Exception e) {
            log.warn("Langfuse usage report failed: {}", e.getMessage());
            return UsageReport.error("拉取 Langfuse 失败：" + e.getMessage());
        }
    }

    /** GET JSON，非 200 抛异常（429 抛 RateLimitedException，由 report 做冷却处理）。 */
    private String getJson(String url) throws RateLimitedException {
        ResponseEntity<String> resp = rest.get().uri(url).exchange((req, res) -> {
            String body = res.getBody() != null
                    ? new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8) : "";
            return ResponseEntity.status(res.getStatusCode()).body(body);
        });
        int code = resp.getStatusCode().value();
        if (code == 200) return resp.getBody();
        if (code == 429) throw new RateLimitedException(parseRetryAfter(resp.getBody()));
        throw new RuntimeException("Langfuse API 返回 " + code + ": " + truncate(resp.getBody()));
    }

    /** 限流异常：携带建议等待秒数，不继承 Exception 以免被通用 catch 吞掉。 */
    private static final class RateLimitedException extends Exception {
        final int retryAfter;
        RateLimitedException(int retryAfter) { super("rate limited"); this.retryAfter = retryAfter; }
    }

    private static int parseRetryAfter(String body) {
        if (body == null) return 0;
        Matcher m = RETRY_AFTER.matcher(body);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) : s);
    }

    private List<Gen> fetchGenerations(Instant from, Instant to) throws Exception {
        List<Gen> out = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            String url = baseUrl + "/api/public/observations?type=GENERATION&fromStartTime=" + iso(from)
                    + "&toStartTime=" + iso(to) + "&page=" + page + "&limit=100";
            JsonNode root = objectMapper.readTree(getJson(url));
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) break;
            for (JsonNode o : data) {
                // 每个 span 现已注入 langfuse.user.id（LangfuseUserPropagationFilter），
                // 优先从该 observation 自身的 metadata 读取，避免依赖 trace join。
                String user = metaAttr(o, "langfuse.user.id");
                out.add(new Gen(str(o, "traceId"), str(o, "model"),
                        num(o, "promptTokens"), num(o, "completionTokens"), num(o, "totalTokens"),
                        str(o, "startTime"), user));
            }
            if (page >= totalPages(root, page)) break;
        }
        return out;
    }

    private Map<String, String> fetchTraceUsers(Instant from, Instant to) throws Exception {
        Map<String, String> map = new HashMap<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            String url = baseUrl + "/api/public/traces?fromStartTime=" + iso(from)
                    + "&toStartTime=" + iso(to) + "&page=" + page + "&limit=100";
            JsonNode root = objectMapper.readTree(getJson(url));
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) break;
            for (JsonNode t : data) {
                String id = str(t, "id");
                if (id.isBlank()) continue;
                String userId = str(t, "userId");
                if (userId.isBlank()) {
                    userId = metaAttr(t, "langfuse.user.id");
                }
                map.put(id, userId.isBlank() ? "未知用户" : userId);
            }
            if (page >= totalPages(root, page)) break;
        }
        return map;
    }

    private UsageReport aggregate(List<Gen> gens, Map<String, String> traceUsers) {
        long totalTokens = 0, totalRequests = 0;
        Map<String, ModelAgg> byModel = new LinkedHashMap<>();
        Map<LocalDate, Long> byDay = new TreeMap<>();
        Map<LocalDate, Map<String, Long>> dayModelTokens = new TreeMap<>();
        Map<LocalDate, Map<String, BigDecimal>> dayModelCost = new TreeMap<>();
        Map<String, Long> byUser = new HashMap<>();
        BigDecimal totalCost = BigDecimal.ZERO;
        boolean allUnknownUser = true;
        for (Gen g : gens) {
            totalTokens += g.total;
            totalRequests++;
            String modelKey = g.model.isBlank() ? "未知模型" : g.model;
            ModelAgg ma = byModel.computeIfAbsent(modelKey, ModelAgg::new);
            ma.prompt += g.prompt;
            ma.completion += g.completion;
            ma.total += g.total;
            ma.count++;
            BigDecimal genCost = BigDecimal.ZERO;
            ModelPrice p = priceMap.get(g.model);
            if (p != null) {
                genCost = BigDecimal.valueOf(g.prompt).multiply(p.input)
                        .add(BigDecimal.valueOf(g.completion).multiply(p.output))
                        .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
                ma.cost = ma.cost.add(genCost);
                totalCost = totalCost.add(genCost);
            }
            LocalDate d = g.startTime.isBlank() ? LocalDate.now(BJ) : Instant.parse(g.startTime).atZone(BJ).toLocalDate();
            byDay.merge(d, g.total, Long::sum);
            dayModelTokens.computeIfAbsent(d, k -> new HashMap<>()).merge(modelKey, g.total, Long::sum);
            if (genCost.compareTo(BigDecimal.ZERO) != 0) {
                dayModelCost.computeIfAbsent(d, k -> new HashMap<>()).merge(modelKey, genCost, BigDecimal::add);
            }
            // 优先用该 observation 自身的 langfuse.user.id，回退到 trace join
            String rawUser = (!g.user.isBlank() ? g.user : traceUsers.getOrDefault(g.traceId, "未知用户"));
            String user = normalizeUserId(rawUser);
            if (!"未知用户".equals(user)) allUnknownUser = false;
            byUser.merge(shortenUser(user), g.total, Long::sum);
        }
        long maxDay = byDay.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        List<ModelRow> modelRows = byModel.values().stream()
                .map(m -> new ModelRow(m.model, m.prompt, m.completion, m.total, m.count, m.cost.setScale(4, RoundingMode.HALF_UP)))
                .sorted(Comparator.comparingLong(ModelRow::total).reversed()).toList();
        List<DayRow> dayRows = byDay.entrySet().stream().map(e -> new DayRow(e.getKey().toString(), e.getValue())).toList();
        List<UserRow> userRows = byUser.entrySet().stream().map(e -> new UserRow(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(UserRow::total).reversed()).limit(20).toList();
        ChartResult chart = buildChart(byDay, dayModelTokens, dayModelCost, modelRows);
        String note = allUnknownUser ? "提示：当前 Langfuse 未记录用户标识（OTel 未透传 langfuse.user.id），按用户排名暂统一显示为「未知用户」。" : null;
        return new UsageReport(false, totalTokens, totalRequests, totalCost.setScale(2, RoundingMode.HALF_UP),
                modelRows, dayRows, userRows, maxDay, null, note, chart.days(), chart.ticks(), chart.labelSkip());
    }

    private static int totalPages(JsonNode root, int current) {
        JsonNode meta = root.get("meta");
        return meta != null && meta.get("totalPages") != null ? meta.get("totalPages").asInt() : current;
    }

    private static String iso(Instant i) { return i.toString(); }
    private static String str(JsonNode n, String k) { JsonNode v = n.get(k); return v == null ? "" : v.asText(""); }
    private static long num(JsonNode n, String k) { JsonNode v = n.get(k); return v == null ? 0 : v.asLong(0); }
    private static String shortenUser(String u) {
        if (u == null || u.isBlank()) return "未知用户";
        if (u.contains("@im.wechat")) return u.substring(0, u.lastIndexOf('@'));
        return u.length() > 24 ? u.substring(0, 24) + "…" : u;
    }

    /**
     * 把微信用户标识归一化为可展示的微信 id。
     * AgentSessionContext 中存的是带实例作用域的 scoped id（managed:&lt;instanceId&gt;:&lt;wxid&gt;），
     * 这里剥掉前缀只保留真正的微信用户 id；原始 wxid 直接原样返回。
     */
    private static String normalizeUserId(String u) {
        if (u == null || u.isBlank()) return "未知用户";
        if (u.startsWith("managed:")) {
            int idx = u.lastIndexOf(':');
            return idx > 0 ? u.substring(idx + 1) : u;
        }
        return u;
    }

    /** 安全读取 observation/trace 的 metadata.attributes["<key>"]（key 可能含点号，不能用 path 逐段解析）。 */
    private static String metaAttr(JsonNode node, String key) {
        JsonNode m = node.path("metadata");
        if (m.isMissingNode()) return "";
        JsonNode a = m.path("attributes");
        if (a.isMissingNode() || !a.isObject()) return "";
        JsonNode v = a.get(key);
        return v == null ? "" : v.asText("");
    }

    private record Gen(String traceId, String model, long prompt, long completion, long total, String startTime, String user) { }
    private record ModelPrice(BigDecimal input, BigDecimal output) {
        ModelPrice(double i, double o) { this(BigDecimal.valueOf(i), BigDecimal.valueOf(o)); }
    }
    private static final class ModelAgg {
        final String model;
        long prompt, completion, total, count;
        BigDecimal cost = BigDecimal.ZERO;
        ModelAgg(String model) { this.model = model; }
    }

    public record UsageReport(boolean unconfigured, long totalTokens, long totalRequests, BigDecimal totalCost,
                               List<ModelRow> byModel, List<DayRow> byDay, List<UserRow> byUser, long maxDayTokens,
                               String errorMessage, String note, List<ChartDay> chartDays, List<YTick> yTicks, int labelSkip) {
        static UsageReport unconfigured(String msg) {
            return new UsageReport(true, 0, 0, BigDecimal.ZERO, List.of(), List.of(), List.of(), 0, msg, null, List.of(), List.of(), 1);
        }
        static UsageReport error(String msg) {
            return new UsageReport(false, 0, 0, BigDecimal.ZERO, List.of(), List.of(), List.of(), 0, msg, null, List.of(), List.of(), 1);
        }
    }
    public record ModelRow(String model, long prompt, long completion, long total, long count, BigDecimal cost) { }
    public record DayRow(String date, long total) { }
    public record UserRow(String user, long total) { }
    public record ChartDay(String date, long total, List<ChartSeg> segments) { }
    public record ChartSeg(String model, long tokens, BigDecimal cost, double x, double y, double w, double h, String fill) { }
    public record YTick(double y, String label) { }
    private record ChartResult(List<ChartDay> days, List<YTick> ticks, int labelSkip) { }

    public static final String[] CHART_PALETTE = {
            "#2d6cdf", "#4caf50", "#ff9800", "#9c27b0", "#00bcd4",
            "#e91e63", "#795548", "#607d8b", "#f44336", "#3f51b5"
    };

    /** 生成按天堆叠柱状图：每天一根柱子，内部按模型分色。坐标已映射到 SVG viewBox 1000×420。 */
    private ChartResult buildChart(Map<LocalDate, Long> byDay,
                                   Map<LocalDate, Map<String, Long>> dayModelTokens,
                                   Map<LocalDate, Map<String, BigDecimal>> dayModelCost,
                                   List<ModelRow> modelOrder) {
        if (byDay.isEmpty()) return new ChartResult(List.of(), List.of(), 1);
        long yMax = niceMax(byDay.values().stream().mapToLong(Long::longValue).max().orElse(0L));
        final int originX = 64, originY = 356, chartW = 916, chartH = 328;
        int n = byDay.size();
        int labelSkip = n <= 12 ? 1 : (n <= 30 ? 3 : (n <= 60 ? 5 : 7));
        double slot = (double) chartW / n;
        double barW = slot * 0.65;
        List<YTick> ticks = new ArrayList<>();
        int tickCount = 5;
        for (int i = 0; i <= tickCount; i++) {
            long v = yMax * i / tickCount;
            double y = originY - (double) v / yMax * chartH;
            ticks.add(new YTick(y, formatCompact(v)));
        }
        List<String> order = modelOrder.stream().map(ModelRow::model).toList();
        List<ChartDay> days = new ArrayList<>();
        int i = 0;
        for (LocalDate d : byDay.keySet()) {
            double x = originX + i * slot + (slot - barW) / 2;
            double cy = originY;
            Map<String, Long> mt = dayModelTokens.getOrDefault(d, Map.of());
            Map<String, BigDecimal> mc = dayModelCost.getOrDefault(d, Map.of());
            List<ChartSeg> segs = new ArrayList<>();
            for (String m : order) {
                long tok = mt.getOrDefault(m, 0L);
                if (tok <= 0) continue;
                BigDecimal c = mc.getOrDefault(m, BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
                double h = yMax > 0 ? (double) tok / yMax * chartH : 0;
                double y = cy - h;
                segs.add(new ChartSeg(m, tok, c, round2(x), round2(y), round2(barW), round2(h), colorForModel(m, order)));
                cy -= h;
            }
            days.add(new ChartDay(d.toString(), byDay.get(d), segs));
            i++;
        }
        return new ChartResult(days, ticks, labelSkip);
    }

    private static String colorForModel(String model, List<String> order) {
        int idx = order.indexOf(model);
        return CHART_PALETTE[Math.max(0, idx) % CHART_PALETTE.length];
    }

    /** 找一个 >= v 的「漂亮」最大值（1/2/5/10 进制倍数），让 Y 轴刻度更规整。 */
    private static long niceMax(long v) {
        if (v <= 0) return 1;
        double pow10 = Math.pow(10, Math.floor(Math.log10(v)));
        double[] ms = {1, 2, 5, 10};
        long best = Long.MAX_VALUE;
        for (double m : ms) {
            long step = (long) (pow10 * m);
            long cand = (long) Math.ceil((double) v / step) * step;
            if (cand >= v && cand < best) best = cand;
        }
        return best == Long.MAX_VALUE ? v : best;
    }

    private static String formatCompact(long v) {
        return String.format("%,d", v);
    }
    private static double round2(double d) { return Math.round(d * 100) / 100.0; }
}
