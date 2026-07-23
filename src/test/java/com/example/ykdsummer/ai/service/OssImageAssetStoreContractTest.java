package com.example.ykdsummer.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.example.ykdsummer.ai.config.OssImageProperties;
import java.io.InputStream;
import java.net.URL;
import org.junit.jupiter.api.Test;

/** 验证生产仓库只调用 OSS 模拟对象，不写本机 .ai-assets/images。 */
class OssImageAssetStoreContractTest {

    @Test
    void writesImageMetadataAndPointerToOssAndUsesShortReadUrl() throws Exception {
        OssImageProperties properties = new OssImageProperties();
        properties.setEndpoint("https://oss.example.invalid");
        properties.setAccessKeyId("test-id");
        properties.setAccessKeySecret("test-secret");
        properties.setBucketName("test-bucket");
        properties.setPrefix("bot/images");
        OSS oss = mock(OSS.class);
        when(oss.generatePresignedUrl(eq("test-bucket"), contains("/v1.png"), any()))
                .thenReturn(new URL("https://signed.example/image.png"));
        OssImageAssetStore store = new OssImageAssetStore(properties, oss);

        var image = store.saveGenerated("wechat-user", "一只猫", new byte[]{1, 2, 3}, null);

        assertThat(image.file().toString().replace('\\', '/')).startsWith("bot/images/").endsWith("/v1.png");
        assertThat(store.current("wechat-user")).contains(image);
        assertThat(store.signedReadUrl(image)).isEqualTo("https://signed.example/image.png");
        verify(oss).putObject(eq("test-bucket"), contains("/v1.png"), any(InputStream.class), any(ObjectMetadata.class));
        verify(oss).putObject(eq("test-bucket"), contains("metadata.properties"), any(InputStream.class), any(ObjectMetadata.class));
        verify(oss).putObject(eq("test-bucket"), contains("current-image.properties"), any(InputStream.class), any(ObjectMetadata.class));
    }
}
