package com.example.ykdsummer.bot.session;

import com.example.ykdsummer.bot.config.ILinkProperties;
import com.example.ykdsummer.bot.runtime.ILinkRuntimeState;
import io.github.morningwn.client.ILinkAuthSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ILinkSessionStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsAndReloadsSessionAndCursor() {
        Path sessionFile = temporaryDirectory.resolve("state").resolve("session.properties");
        ILinkProperties settings = new ILinkProperties();
        settings.setSessionFile(sessionFile);
        ILinkRuntimeState runtimeState = new ILinkRuntimeState();
        ILinkSessionStore store = new ILinkSessionStore(settings, runtimeState);
        ILinkAuthSession expected = new ILinkAuthSession(
                "secret-token",
                "https://ilinkai.weixin.qq.com",
                "demo@im.bot",
                "user@im.wechat"
        );

        store.persistSession(expected);
        String committed = store.confirmGetUpdatesBuf("", "next-cursor", List.of(), true);

        assertEquals("next-cursor", committed);
        assertTrue(Files.exists(sessionFile));

        ILinkSessionStore reloaded = new ILinkSessionStore(settings, new ILinkRuntimeState());
        ILinkAuthSession actual = reloaded.loadSession();

        assertNotNull(actual);
        assertEquals(expected, actual);
        assertEquals("next-cursor", reloaded.loadCursor());
    }

    @Test
    void doesNotCommitCursorWhenMessageHandlingFailed() {
        ILinkProperties settings = new ILinkProperties();
        settings.setSessionFile(temporaryDirectory.resolve("session.properties"));
        ILinkSessionStore store = new ILinkSessionStore(settings, new ILinkRuntimeState());

        String committed = store.confirmGetUpdatesBuf("old", "new", List.of(), false);

        assertEquals("old", committed);
        assertEquals("", store.loadCursor());
    }

    @Test
    void clearsExpiredSessionFile() {
        Path sessionFile = temporaryDirectory.resolve("session.properties");
        ILinkProperties settings = new ILinkProperties();
        settings.setSessionFile(sessionFile);
        ILinkSessionStore store = new ILinkSessionStore(settings, new ILinkRuntimeState());
        ILinkAuthSession session = new ILinkAuthSession("token", "base", "account", "user");
        store.persistSession(session);
        assertTrue(Files.exists(sessionFile));

        store.clearSession(session);

        assertFalse(Files.exists(sessionFile));
    }
}
