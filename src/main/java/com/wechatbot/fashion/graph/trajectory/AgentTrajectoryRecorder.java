package com.wechatbot.fashion.graph.trajectory;

/**
 * Agent 轨迹记录接口：run 维度（一次穿搭图调用）+ step 维度（节点 / 模型调用 / 工具调用）。
 *
 * <p>实现必须「记录失败不影响业务」：所有写库异常内部吞掉，只在日志告警；持久化未开启时
 * 使用 {@link #noop()}。</p>
 */
public interface AgentTrajectoryRecorder {

    /** run 开始时的元信息。 */
    record RunStart(
            String runId,
            String threadId,
            String userId,
            String query,
            String graphVersion,
            String promptVersion,
            String model) {
    }

    /** 单步轨迹（一个图节点、一次模型调用或一次工具调用）。 */
    record Step(
            String runId,
            String nodeName,
            String stepType,
            String status,
            String model,
            String toolName,
            String inputSummary,
            String outputSummary,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long durationMs,
            String errorMessage) {

        public static final String TYPE_NODE = "NODE";
        public static final String TYPE_MODEL = "MODEL";
        public static final String TYPE_TOOL = "TOOL";
        public static final String TYPE_MEMORY = "MEMORY";

        public static final String STATUS_SUCCESS = "SUCCESS";
        public static final String STATUS_FAILED = "FAILED";
    }

    void startRun(RunStart start);

    void recordStep(Step step);

    /** 结束 run：写入状态、时长、错误，并按 step 聚合 total_tokens。 */
    void finishRun(String runId, String status, long durationMs, String errorMessage);

    static AgentTrajectoryRecorder noop() {
        return new AgentTrajectoryRecorder() {
            @Override
            public void startRun(RunStart start) {
            }

            @Override
            public void recordStep(Step step) {
            }

            @Override
            public void finishRun(String runId, String status, long durationMs, String errorMessage) {
            }
        };
    }
}
