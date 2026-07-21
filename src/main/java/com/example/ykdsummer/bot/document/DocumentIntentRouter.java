package com.example.ykdsummer.bot.document;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 用本地、可预测的规则区分“只分析文件”和“真正修改文件”。
 * 无法确定时返回 AMBIGUOUS，调用方必须先确认，不能偷偷创建新版本。
 */
@Component
public class DocumentIntentRouter {

    private static final List<String> EDIT_WORDS = List.of(
            "修改", "编辑", "改成", "改为", "替换", "删除", "删掉", "增加", "新增",
            "添加", "重写", "调整", "合并", "拆分", "润色", "修正", "优化", "改短", "改长",
            "整理成", "改写成", "按你的理解", "按照你的理解"
    );
    private static final List<String> GENERATE_WORDS = List.of(
            "生成一个", "生成一份", "另生成", "另外生成", "重新生成一个", "重新生成一份",
            "创建一个", "创建一份", "另写一份", "输出一个新", "输出一份新"
    );
    private static final List<String> ANALYZE_WORDS = List.of(
            "分析", "提问", "评价", "评估", "为什么", "是否", "怎么样", "合理吗", "哪里有问题",
            "有什么问题", "问题是什么", "有问题", "检查一下", "审阅", "怎么看", "解释"
    );

    public Decision route(String input) {
        String text = input == null ? "" : input.strip();
        if (text.isBlank()) {
            return new Decision(Intent.AMBIGUOUS, "");
        }
        Decision prefixed = prefixedDecision(text);
        if (prefixed != null) {
            return prefixed;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, GENERATE_WORDS)) {
            return new Decision(Intent.GENERATE, text);
        }
        if (containsAny(normalized, EDIT_WORDS)
                || ((text.startsWith("把") || text.startsWith("将") || text.startsWith("请把") || text.startsWith("请将"))
                && text.contains("改"))) {
            return new Decision(Intent.EDIT, text);
        }
        if (text.endsWith("?") || text.endsWith("？")
                || containsAny(normalized, ANALYZE_WORDS)
                || ((text.startsWith("我认为") || text.startsWith("我觉得")) && text.contains("问题"))) {
            return new Decision(Intent.ANALYZE, text);
        }
        return new Decision(Intent.AMBIGUOUS, text);
    }

    private static Decision prefixedDecision(String text) {
        for (String prefix : List.of("分析：", "分析:", "提问：", "提问:", "问：", "问:")) {
            if (text.startsWith(prefix)) {
                return new Decision(Intent.ANALYZE, text.substring(prefix.length()).strip());
            }
        }
        for (String prefix : List.of("修改：", "修改:", "编辑：", "编辑:")) {
            if (text.startsWith(prefix)) {
                return new Decision(Intent.EDIT, text.substring(prefix.length()).strip());
            }
        }
        for (String prefix : List.of("生成文件：", "生成文件:", "生成：", "生成:")) {
            if (text.startsWith(prefix)) {
                return new Decision(Intent.GENERATE, text.substring(prefix.length()).strip());
            }
        }
        return null;
    }

    private static boolean containsAny(String text, List<String> words) {
        return words.stream().anyMatch(text::contains);
    }

    public enum Intent { ANALYZE, EDIT, GENERATE, AMBIGUOUS }

    public record Decision(Intent intent, String instruction) { }
}
