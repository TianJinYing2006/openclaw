package com.wechatbot.fashion.bot.file;

import com.wechatbot.fashion.ai.model.AiFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileSessionServiceTest {

    @Test
    void consumesTheUploadedFileOnlyOnceAndCanClearIt() {
        FileSessionService sessions = new FileSessionService();
        AiFile first = new AiFile("first.docx", "application/octet-stream", new byte[]{1});

        sessions.cache("user", first);
        assertThat(sessions.consume("user")).contains(first);
        assertThat(sessions.consume("user")).isEmpty();

        sessions.cache("user", first);
        sessions.clear("user");
        assertThat(sessions.consume("user")).isEmpty();
        assertThat(sessions.size()).isZero();
    }
}
