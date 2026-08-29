package com.wechatbot.fashion.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectListing;
import com.wechatbot.fashion.ai.config.OssImageProperties;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 真实 OSS 冒烟测试。默认跳过；只在临时设置 OSS_LIVE_TEST=true 与四个 ALIOSS_* 环境变量后执行。
 * 每次使用随机前缀，并在 finally 中删除本次创建的所有对象。
 */
@EnabledIfEnvironmentVariable(named = "OSS_LIVE_TEST", matches = "true")
class OssImageAssetStoreLiveTest {

    @Test
    @Timeout(90)
    void writesReadsSignsVersionsAndCleansUpRealOssObjects() throws Exception {
        String runPrefix = "ilink-bot/live-verification/" + UUID.randomUUID();
        String userId = "oss-live-test-" + UUID.randomUUID();
        OssImageProperties properties = new OssImageProperties();
        properties.setEndpoint(required("ALIOSS_ENDPOINT"));
        properties.setAccessKeyId(required("ALIOSS_ACCESS_KEY_ID"));
        properties.setAccessKeySecret(required("ALIOSS_ACCESS_KEY_SECRET"));
        properties.setBucketName(required("ALIOSS_BUCKET_NAME"));
        properties.setPrefix(runPrefix);
        properties.setSignedUrlTtl(Duration.ofMinutes(2));

        OSS client = new OSSClientBuilder().build(
                properties.getEndpoint(), properties.getAccessKeyId(), properties.getAccessKeySecret());
        OssImageAssetStore store = new OssImageAssetStore(properties, client);
        String userPrefix = runPrefix + "/" + Integer.toHexString(userId.hashCode()) + "/";
        boolean objectCreated = false;
        try {
            byte[] original = {1, 2, 3, 4};
            var v1 = store.saveGenerated(userId, "真实 OSS 冒烟图片", original, null);
            objectCreated = true;
            assertThat(store.readBytes(v1)).containsExactly(original);

            String signedUrl = store.signedReadUrl(v1);
            assertThat(signedUrl).startsWith("http");
            try (InputStream input = new URL(signedUrl).openStream()) {
                assertThat(input.readAllBytes()).containsExactly(original);
            }

            store.annotate(userId, v1.assetId(), "测试标签：蓝色方块");
            var v2 = store.saveRevision(userId, v1.assetId(), "第二版本", new byte[]{5, 6, 7}, null);
            var v3 = store.restore(userId, v1.assetId(), 1);

            assertThat(v2.version()).isEqualTo(2);
            assertThat(v3.version()).isEqualTo(3);
            assertThat(store.readBytes(v3)).containsExactly(original);
            assertThat(v3.tags()).contains("蓝色方块");
            assertThat(store.current(userId)).contains(v3);
        } finally {
            if (objectCreated) {
                deleteAllUnderPrefix(client, properties.getBucketName(), userPrefix);
            }
            client.shutdown();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少真实 OSS 测试环境变量：" + name);
        }
        return value;
    }

    private static void deleteAllUnderPrefix(OSS client, String bucket, String prefix) {
        String marker = null;
        do {
            ObjectListing listing = client.listObjects(new ListObjectsRequest(bucket)
                    .withPrefix(prefix)
                    .withMarker(marker)
                    .withMaxKeys(1000));
            listing.getObjectSummaries().forEach(object -> client.deleteObject(bucket, object.getKey()));
            marker = listing.isTruncated() ? listing.getNextMarker() : null;
        } while (marker != null);
    }
}
