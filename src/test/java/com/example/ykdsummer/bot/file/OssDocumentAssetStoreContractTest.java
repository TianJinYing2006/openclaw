package com.example.ykdsummer.bot.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.example.ykdsummer.ai.config.OssDocumentProperties;
import com.example.ykdsummer.ai.config.OssImageProperties;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class OssDocumentAssetStoreContractTest {
    @Test
    void writesDocumentAndCurrentPointerToOss() {
        OssImageProperties connection = connection();
        OssDocumentProperties properties = new OssDocumentProperties();
        properties.setEnabled(true);
        properties.setPrefix("bot/documents");
        OSS oss = mock(OSS.class);
        OssDocumentAssetStore store = new OssDocumentAssetStore(connection, properties, oss);

        var document = store.create("wechat-user", "周报", "txt", "本周进展".getBytes(), "测试文档");

        assertThat(document.file().toString().replace('\\', '/')).startsWith("bot/documents/").endsWith(".txt");
        assertThat(store.current("wechat-user")).contains(document);
        assertThat(store.selectCurrent("wechat-user", document.assetId(), document.version())).contains(document);
        verify(oss).putObject(eq("test-bucket"), contains(".txt"), any(InputStream.class), any(ObjectMetadata.class));
        verify(oss).putObject(eq("test-bucket"), contains("metadata.properties"), any(InputStream.class), any(ObjectMetadata.class));
        verify(oss, times(2)).putObject(eq("test-bucket"), contains("current-document.properties"), any(InputStream.class), any(ObjectMetadata.class));
    }

    private static OssImageProperties connection() {
        OssImageProperties properties = new OssImageProperties();
        properties.setEndpoint("https://oss.example.invalid");
        properties.setAccessKeyId("test-id");
        properties.setAccessKeySecret("test-secret");
        properties.setBucketName("test-bucket");
        return properties;
    }
}
