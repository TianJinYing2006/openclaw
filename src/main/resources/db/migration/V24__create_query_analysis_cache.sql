-- QueryAnalyzer 分析结果持久缓存：固化任意用户输入对应的检索词，
-- 消除 LLM 分析跨进程漂移（实测同配置 top-5 在 40%~61% 摆动，噪声 >20pp），
-- 并避免相同查询重复消耗 LLM token（生产「同表述同分析」）。
-- 配套：QueryAnalysisStore / QueryAnalyzer（内存 Caffeine → MySQL 持久层 → LLM）。
CREATE TABLE fashion_query_analysis_cache (
    input_hash       CHAR(64) NOT NULL COMMENT 'sha256(contextualInput=画像+用户输入)',
    contextual_input TEXT     NOT NULL COMMENT '缓存 key 原文本（调试用）',
    analyzed_json    TEXT     NOT NULL COMMENT 'AnalyzedQuery(含 QueryParams) 序列化',
    updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (input_hash)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'QueryAnalyzer 分析结果持久缓存（固化检索词）';