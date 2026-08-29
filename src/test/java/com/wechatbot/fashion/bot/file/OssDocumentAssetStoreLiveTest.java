package com.wechatbot.fashion.bot.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectListing;
import com.wechatbot.fashion.ai.config.OssDocumentProperties;
import com.wechatbot.fashion.ai.config.OssImageProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit opt-in real OSS document verification. Test objects are always removed in finally. */
@EnabledIfEnvironmentVariable(named = "OSS_LIVE_TEST", matches = "true")
class OssDocumentAssetStoreLiveTest {
    @Test
    @Timeout(90)
    void writesReadsRestoresAndCleansUpRealOssDocuments() {
        String runPrefix = "ilink-bot/live-document-verification/" + UUID.randomUUID();
        OssImageProperties connection = new OssImageProperties();
        connection.setEndpoint(required("ALIOSS_ENDPOINT"));
        connection.setAccessKeyId(required("ALIOSS_ACCESS_KEY_ID"));
        connection.setAccessKeySecret(required("ALIOSS_ACCESS_KEY_SECRET"));
        connection.setBucketName(required("ALIOSS_BUCKET_NAME"));
        OssDocumentProperties properties = new OssDocumentProperties();
        properties.setEnabled(true);
        properties.setPrefix(runPrefix);
        OSS client = new OSSClientBuilder().build(connection.getEndpoint(), connection.getAccessKeyId(), connection.getAccessKeySecret());
        OssDocumentAssetStore store = new OssDocumentAssetStore(connection, properties, client);
        String userId = "oss-document-live-" + UUID.randomUUID();
        String userPrefix = runPrefix + "/" + Integer.toHexString(userId.hashCode()) + "/";
        try {
            var v1 = store.create(userId, "真实 OSS 文档", "txt", "第一版".getBytes(), "初始版本");
            assertThat(store.readBytes(v1)).isEqualTo("第一版".getBytes());
            var v2 = store.revise(userId, v1.assetId(), "真实 OSS 文档", "txt", "第二版".getBytes(), "更新正文");
            var v3 = store.restore(userId, v1.assetId(), 1);
            assertThat(v2.version()).isEqualTo(2);
            assertThat(v3.version()).isEqualTo(3);
            assertThat(store.readBytes(v3)).isEqualTo("第一版".getBytes());
            assertThat(store.current(userId)).contains(v3);
        } finally {
            deleteAllUnderPrefix(client, connection.getBucketName(), userPrefix);
            client.shutdown();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少真实 OSS 测试环境变量：" + name);
        return value;
    }

    private static void deleteAllUnderPrefix(OSS client, String bucket, String prefix) {
        String marker = null;
        do {
            ObjectListing listing = client.listObjects(new ListObjectsRequest(bucket)
                    .withPrefix(prefix).withMarker(marker).withMaxKeys(1000));
            listing.getObjectSummaries().forEach(object -> client.deleteObject(bucket, object.getKey()));
            marker = listing.isTruncated() ? listing.getNextMarker() : null;
        } while (marker != null);
    }
}
