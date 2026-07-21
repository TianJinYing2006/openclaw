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
            "修改", "编辑", "更改", "改一下", "改成", "改为", "换成", "换为", "替换",
            "删除", "删掉", "去掉", "移除", "增加", "新增", "添加", "补充", "补全", "续写",
            "重写", "调整", "更新", "合并", "拆分", "润色", "修正", "修订", "校对", "优化",
            "完善", "精简", "扩写", "缩写", "改短", "改长", "美化", "翻译成", "转换成",
            "统一格式", "重新排版", "重排", "整理成", "改写成", "按你的理解", "按照你的理解"
    );
    private static final List<String> GENERATE_WORDS = List.of(
            "生成一个", "生成一份", "生成一篇", "生成个", "生成份",
            "另生成", "另外生成", "重新生成", "帮我生成", "给我生成", "替我生成", "请生成",
            "创建一个", "创建一份", "创建个", "创建份", "新建一个", "新建一份",
            "制作一个", "制作一份", "制作个", "制作份",
            "写一份", "写一个", "写份", "写个", "另写", "撰写", "编写",
            "做一份", "做一个", "做份", "做个", "出一份", "出一个", "出个",
            "整理出", "导出", "输出为", "输出一个新", "输出一份新"
    );
    private static final List<String> ANALYZE_WORDS = List.of(
            "分析", "提问", "评价", "评估", "为什么", "是否", "怎么样", "合理吗", "哪里有问题",
            "有什么问题", "问题是什么", "有问题", "检查一下", "审阅", "怎么看", "解释",
            "总结", "概括", "提炼", "归纳", "梳理", "列出", "找出", "指出", "提取",
            "看一下", "看看", "读一下", "给些建议", "提点建议", "主要内容", "核心内容",
            "核心观点", "内容是什么", "重点是什么", "讲了什么", "说了什么"
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
        // 生成优先于编辑：例如“把原文整理成一份 Word 报告”同时含“整理成”，
        // 但用户要的是独立文件，不应覆盖当前版本。
        if (isGenerationRequest(normalized)) {
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

    private static boolean isGenerationRequest(String text) {
        if (containsAny(text, GENERATE_WORDS)) {
            return true;
        }
        // “生成”出现在句首或明确的动作结构中才算请求；
        // “刚才生成的文件有什么问题”中的“生成”只是修饰语，应继续走分析。
        return text.matches("^(请|麻烦|帮我|给我|替我|我想|我要)?(再|重新|另外|直接)?"
                + "(生成|创建|新建|制作|撰写|编写|写|做|整理出|导出|输出为).+")
                || text.matches("^(把|将|根据|基于|参考).+(生成|创建|制作|写成|做成|导出|输出).+");
    }

    public enum Intent { ANALYZE, EDIT, GENERATE, AMBIGUOUS }

    public record Decision(Intent intent, String instruction) { }
}
