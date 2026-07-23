package com.example.ykdsummer.ai.orchestration;

import com.example.ykdsummer.storage.FileStorageService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次 Agent 会话的上下文。由 {@link AgentCoordinator} 在 start 时创建。
 *
 * <p>记录 userId、sessionId、文件路径、执行历史和终止条件。</p>
 */
public class AgentContext {

    private final String userId;
    private final String sessionId;
    private final FileStorageService.Session session;
    private final String userPrompt;
    private final List<String> filePaths;
    private final List<StepRecord> history;
    private int iteration;
    private boolean terminated;

    AgentContext(String userId, String sessionId, FileStorageService.Session session, String userPrompt) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.session = session;
        this.userPrompt = userPrompt;
        this.filePaths = new ArrayList<>();
        this.history = new ArrayList<>();
        this.iteration = 0;
        this.terminated = false;
    }

    /** 添加输入文件路径。 */
    public void addInputFile(String filePath) {
        filePaths.add(filePath);
    }

    /** 记录一次工具调用的步骤。 */
    public void recordStep(String action, String params, String result) {
        history.add(new StepRecord(iteration, action, params, result));
        iteration++;
    }

    public void markTerminated() {
        this.terminated = true;
    }

    // ========== getters ==========

    public String userId() { return userId; }
    public String sessionId() { return sessionId; }
    public FileStorageService.Session session() { return session; }
    public String userPrompt() { return userPrompt; }
    public List<String> filePaths() { return Collections.unmodifiableList(filePaths); }
    public int iteration() { return iteration; }
    public boolean isTerminated() { return terminated; }
    public List<StepRecord> history() { return Collections.unmodifiableList(history); }

    /** 一次工具调用的执行记录。 */
    public record StepRecord(int iteration, String action, String params, String result) {}
}
