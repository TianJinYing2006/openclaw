package com.wechatbot.fashion.admin.web;

import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.rag.QueryAnalyzer;
import com.wechatbot.fashion.ai.fashion.look.rag.RagFlowKnowledgeService;
import com.wechatbot.fashion.ai.fashion.look.rag.RagFlowKnowledgeService.RetrievalDebug;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 检索调试台：输入自然语言 query，展示 RAG 检索链路三段分数变化。
 *
 * <p>面试演示用：直观看到 QueryAnalyzer 结构化参数 → 原始候选池 → 规则重排 → 最终结果，
 * 对应简历上的 24.6% 严格匹配 + 100% 语义匹配数据。
 *
 * <p>仅在 admin 启用时生效；RAGFlow provider 未配置时页面显示提示，不报错。
 */
@Controller
@RequestMapping("/admin/retrieval")
@ConditionalOnProperty(prefix = "app.admin", name = "enabled", havingValue = "true")
public class RetrievalDebugController {
    private static final Logger log = LoggerFactory.getLogger(RetrievalDebugController.class);

    private final QueryAnalyzer queryAnalyzer;
    private final RagFlowKnowledgeService ragFlowService;

    public RetrievalDebugController(QueryAnalyzer queryAnalyzer,
                                    @Autowired(required = false) RagFlowKnowledgeService ragFlowService) {
        this.queryAnalyzer = queryAnalyzer;
        this.ragFlowService = ragFlowService;
    }

    @GetMapping
    public String form(Model model) {
        model.addAttribute("ragFlowAvailable", ragFlowService != null);
        model.addAttribute("activePage", "retrieval");
        return "admin/retrieval";
    }

    @PostMapping
    public String debug(@RequestParam String query, Model model) {
        model.addAttribute("query", query);
        model.addAttribute("ragFlowAvailable", ragFlowService != null);
        model.addAttribute("activePage", "retrieval");
        if (ragFlowService == null) {
            model.addAttribute("error", "RAGFlow 未配置（app.fashion.rag.provider != ragflow），调试台不可用。");
            return "admin/retrieval";
        }
        if (query == null || query.isBlank()) {
            model.addAttribute("error", "请输入检索词。");
            return "admin/retrieval";
        }
        try {
            AnalyzedQuery analyzed = queryAnalyzer.analyze(query);
            RetrievalDebug debug = ragFlowService.retrieveForDebug(analyzed);
            model.addAttribute("debug", debug);
        } catch (Exception e) {
            log.warn("Retrieval debug failed for query '{}': {}", query, e.getMessage());
            model.addAttribute("error", "检索失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        return "admin/retrieval";
    }
}
