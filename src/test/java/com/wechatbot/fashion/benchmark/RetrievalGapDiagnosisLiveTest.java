package com.wechatbot.fashion.benchmark;

import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.rag.QueryAnalyzer;
import com.wechatbot.fashion.ai.fashion.look.rag.RagFlowClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * P0 检索缺口诊断：gt 实际排名分布。
 *
 * <p>对评测集（fashion_conversations 带 reference_outfit_id 的真实查询）逐条取
 * RAGFlow top-20 候选池（无 rerank、无多样性、与 greedyStabilityRerun 同口径），
 * 定位 ground truth 的实际排名，把 miss 分类为：
 * <ul>
 *   <li>rank 1        —— top-1 命中</li>
 *   <li>rank 2~5      —— top-5 命中（不含 top-1）</li>
 *   <li>rank 6~20     —— 排序问题（rerank / 保底策略可救）</li>
 *   <li>池外（-1）     —— 召回缺失（查询改写 / 权重 / 融合可救）</li>
 * </ul>
 * 输出逐条明细 + 汇总，作为后续优化（rerank、vw grid、改写）的基线快照。
 *
 * <p>运行：{@code RESUME_BENCH_LIVE=true RESUME_BENCH_RAG_PROVIDER=ragflow mvn test -Dtest=RetrievalGapDiagnosisLiveTest}
 * <p>结果重定向：{@code > logs/p0_gtrank_diagnosis.log 2>&1}（Windows 下 surefire 中文为 GBK，解析需 iconv）。
 */
@EnabledIfEnvironmentVariable(named = "RESUME_BENCH_LIVE", matches = "true")
@SpringBootTest(
        properties = {
                "ilink.enabled=false",
                "app.admin.enabled=false",
                "app.persistence.enabled=true",
                "app.persistence.redis.enabled=false",
                "app.fashion.semantic.enabled=false",
                "app.fashion.rag.provider=${RESUME_BENCH_RAG_PROVIDER:mysql}",
                "app.fashion.rag.diversity.enabled=false",
                "app.fashion.reference.enabled=false",
                "app.fashion.outfit-recommendation.enabled=false",
                "app.ai.usage.daily-token-limit=999999999",
                "app.web-search.provider=bocha",
                "app.weather.provider=uapis"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class RetrievalGapDiagnosisLiveTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private QueryAnalyzer queryAnalyzer;
    @Autowired(required = false) private RagFlowClient ragFlowClient;

    @Test
    void gtRankDistribution() {
        System.out.println("\n===== [P0] 检索缺口诊断：gt 实际排名分布（候选池 20，无 rerank，与稳定性评测同口径） =====");
        if (ragFlowClient == null) {
            System.out.println("需 provider=ragflow（RESUME_BENCH_RAG_PROVIDER=ragflow）才能运行。");
            return;
        }
        List<RealQuery> queries = loadQueries();
        if (queries.isEmpty()) {
            System.out.println("fashion_conversations 无带 reference_outfit_id 的记录，无法评测。");
            return;
        }

        int total = queries.size();
        int top1Hits = 0;
        int top5Hits = 0;
        int rank6to7 = 0;   // rerank 高概率可救（差 1~2 名）
        int rank8to12 = 0;  // rerank 中概率可救
        int rank13to20 = 0; // rerank 低概率可救
        int recallMiss = 0; // 池外，查询改写/权重/融合可救
        int analysisFailures = 0;

        List<String> details = new ArrayList<>();
        List<int[]> machineRows = new ArrayList<>(); // {hit, idx}，汇总重算唯一真源
        for (RealQuery q : queries) {
            String gt = normalizeOutfitId(q.groundTruth());
            AnalyzedQuery aq;
            try {
                aq = queryAnalyzer.analyze(q.userInput());
            } catch (Exception e) {
                aq = AnalyzedQuery.fallback(q.userInput());
                analysisFailures++;
            }
            String searchQuestion = buildSearchQuestion(aq);
            List<String> poolIds;
            try {
                poolIds = ragFlowClient.retrieve(searchQuestion, 20, "", 0.2).stream()
                        .map(c -> normalizeOutfitId(extractDocName(c)))
                        .toList();
            } catch (Exception e) {
                poolIds = List.of();
                details.add(String.format("%-7s | query=%s | gt=%s | RETRIEVE ERROR: %s",
                        "ERR", truncate(q.userInput()), gt, e.getMessage()));
                continue;
            }
            int idx = poolIds.indexOf(gt);
            String top5 = poolIds.size() <= 5 ? poolIds.toString()
                    : poolIds.subList(0, 5).toString();
            // 机器可读对拍行（与 Python 脚本 join 用，query\tgt\thit\trank\tpool）
            System.out.println("MACHINE\t" + q.userInput().replace("\t", " ").replace("\n", " ")
                    + "\t" + gt + "\t" + (idx >= 0 && idx < 5 ? 1 : 0) + "\t" + (idx + 1) + "\t" + poolIds);
            machineRows.add(new int[]{idx >= 0 && idx < 5 ? 1 : 0, idx});
            String cls;
            if (idx == 0) { top1Hits++; top5Hits++; cls = "TOP-1"; }
            else if (idx == 1) { top5Hits++; cls = "RANK-2"; }
            else if (idx <= 4) { top5Hits++; cls = "RANK-" + (idx + 1); }
            else if (idx <= 6) { rank6to7++; cls = "RANK-" + (idx + 1) + " [6~7 高概率可救]"; }
            else if (idx <= 11) { rank8to12++; cls = "RANK-" + (idx + 1) + " [8~12 中概率可救]"; }
            else if (idx <= 19) { rank13to20++; cls = "RANK-" + (idx + 1) + " [13~20 低概率可救]"; }
            else { recallMiss++; cls = "POOL-OUT [召回缺失]"; }
            details.add(String.format("%-22s | query=%s | gt=%s | top5=%s",
                    cls, truncate(q.userInput()), gt, top5));
        }

        // 汇总一律从逐行 machine 数据重算，保证与明细永远一致
        // （修复：此前循环内累加与明细曾出现 36 vs 26 的自相矛盾；且 idx=-1 池外
        //   必须显式拦截，不能落入 rank 分支）
        top1Hits = 0; top5Hits = 0;
        rank6to7 = 0; rank8to12 = 0; rank13to20 = 0; recallMiss = 0;
        for (int[] m : machineRows) {
            int idx = m[1];
            if (idx < 0) { recallMiss++; continue; }
            if (idx == 0) top1Hits++;
            if (idx < 5) { top5Hits++; continue; }
            if (idx <= 6) rank6to7++;
            else if (idx <= 11) rank8to12++;
            else rank13to20++;
        }

        System.out.println("样本量: " + total + " 条（真实用户查询，gt=系统实际采纳编号）");
        System.out.println("QueryAnalyzer LLM 失败回退次数: " + analysisFailures);
        System.out.println("── 命中 ──");
        System.out.println("top-1 命中:       " + top1Hits + "/" + total + " = " + pct(top1Hits, total));
        System.out.println("top-5 命中:       " + top5Hits + "/" + total + " = " + pct(top5Hits, total));
        System.out.println("── 可救分类（top-5 之外） ──");
        System.out.println("rank 6~7（高概率可救）:    " + rank6to7 + " 条");
        System.out.println("rank 8~12（中概率可救）:   " + rank8to12 + " 条");
        System.out.println("rank 13~20（低概率可救）:  " + rank13to20 + " 条");
        System.out.println("候选池外（召回缺失）:      " + recallMiss + " 条");
        int rescurable = rank6to7 + rank8to12 + rank13to20;
        System.out.println("── 结论 ──");
        System.out.println("排序可救（rank 6~20）: " + rescurable + " 条 → rerank 目标上限 ≈ "
                + pct(top5Hits + rescurable, total));
        System.out.println("召回缺失: " + recallMiss + " 条 → 查询改写/权重/融合目标");
        System.out.println("── 逐条明细 ──");
        details.forEach(System.out::println);
        System.out.println("说明: 候选池 top-20（page_size=20, similarity_threshold=0.2, 无 rerank, 无多样性），"
                + "与 greedyStabilityRerun 同一检索词构造。");
    }

    private List<RealQuery> loadQueries() {
        return jdbc.query(
                "SELECT user_input, reference_outfit_id FROM fashion_conversations "
                        + "WHERE reference_outfit_id <> '' ORDER BY id DESC",
                (rs, rowNum) -> new RealQuery(rs.getString("user_input"), rs.getString("reference_outfit_id")));
    }

    private record RealQuery(String userInput, String groundTruth) {}

    /** 统一 outfit 编号：ref_002 / 002 / [outfit_002] / 2 / 043.md → 002。 */
    private static String normalizeOutfitId(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String cleaned = raw.replace("ref_", "")
                .replace("outfit_", "")
                .replace("[", "")
                .replace("]", "")
                .replace(".md", "")
                .strip();
        try {
            return String.format("%03d", Integer.parseInt(cleaned));
        } catch (NumberFormatException e) {
            return cleaned;
        }
    }

    /** 与 RagFlowKnowledgeService.buildSearchQuestion 同构（原文 + 场景/风格/季节）。 */
    private static String buildSearchQuestion(AnalyzedQuery query) {
        StringBuilder sb = new StringBuilder();
        if (query.originalQuery() != null && !query.originalQuery().isBlank()) {
            sb.append(query.originalQuery());
        }
        if (query.params() != null) {
            if (query.params().scene() != null && !query.params().scene().isBlank()) {
                sb.append(" 场景:").append(query.params().scene());
            }
            if (query.params().styleHint() != null && !query.params().styleHint().isBlank()) {
                sb.append(" 风格:").append(query.params().styleHint());
            }
            if (query.params().season() != null && !query.params().season().isBlank()) {
                sb.append(" 季节:").append(query.params().season());
            }
        }
        return sb.toString().trim();
    }

    private static String extractDocName(RagFlowClient.RagFlowChunk c) {
        String n = c.documentName() != null && !c.documentName().isBlank()
                ? c.documentName() : c.documentKeyword();
        return n == null ? "" : n;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }

    private static String pct(int n, int d) {
        return d == 0 ? "0.0%" : String.format("%.1f%%", 100.0 * n / d);
    }
}