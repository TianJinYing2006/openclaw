package com.wechatbot.fashion.bot.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ILinkDeliveryAuditTest {

    @Test
    void persistsSafeDeliveryMetadataWithoutRawMessageOrUserIds() throws Exception {
        Path auditFile = Files.createTempDirectory("delivery-audit").resolve("events.log");
        ILinkDeliveryAudit audit = new ILinkDeliveryAudit(auditFile);

        audit.gatewayAccepted("private-message-id", "private-user-id", "document", 512);
        audit.failed("private-message-id", "private-user-id", "document", 512,
                new IllegalStateException("CDN upload failed"));

        String content = Files.readString(auditFile);
        assertThat(content).contains("outcome=GATEWAY_ACCEPTED", "outcome=FAILED", "type=document", "bytes=512");
        assertThat(content).contains("IllegalStateException:CDN upload failed");
        assertThat(content).doesNotContain("private-message-id", "private-user-id");
    }
}
