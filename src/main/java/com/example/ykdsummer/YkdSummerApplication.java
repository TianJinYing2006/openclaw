package com.example.ykdsummer;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.config.AiTraceProperties;
import com.example.ykdsummer.ai.config.AiUsageProperties;
import com.example.ykdsummer.ai.config.ChatCompletionsConnectionProperties;
import com.example.ykdsummer.ai.config.ImageOpenAiClientProperties;
import com.example.ykdsummer.ai.config.ImageTaskExecutionProperties;
import com.example.ykdsummer.ai.config.OpenAiClientProperties;
import com.example.ykdsummer.ai.config.OssDocumentProperties;
import com.example.ykdsummer.ai.config.OssImageProperties;
import com.example.ykdsummer.ai.config.RealtimeEvidenceProperties;
import com.example.ykdsummer.ai.fashion.rag.RagFlowProperties;
import com.example.ykdsummer.ai.tool.WebSearchProperties;
import com.example.ykdsummer.admin.config.AdminWebProperties;
import com.example.ykdsummer.bot.config.AliyunTtsProperties;
import com.example.ykdsummer.bot.config.FileProcessingProperties;
import com.example.ykdsummer.bot.config.ILinkProperties;
import com.example.ykdsummer.bot.config.ILinkRateLimitProperties;
import com.example.ykdsummer.bot.config.LongTextOutputProperties;
import com.example.ykdsummer.bot.config.TencentAsrProperties;
import com.example.ykdsummer.bot.config.VideoProcessingProperties;
import com.example.ykdsummer.common.security.ApiSecurityProperties;
import com.example.ykdsummer.common.security.TokenEncryptionProperties;
import com.example.ykdsummer.fashion.config.FashionAnalysisProperties;
import com.example.ykdsummer.fashion.config.FashionCutoutProperties;
import com.example.ykdsummer.fashion.config.FashionReferenceProperties;
import com.example.ykdsummer.fashion.config.FashionSemanticProperties;
import com.example.ykdsummer.fashion.config.FashionTryOnProperties;
import com.example.ykdsummer.fashion.config.OutfitRecommendationProperties;
import com.example.ykdsummer.persistence.PersistenceProperties;
import com.example.ykdsummer.reminder.config.ReminderProperties;
import com.example.ykdsummer.storage.FileStorageProperties;
import com.example.ykdsummer.weather.WeatherProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 整个 Spring Boot 项目的启动入口。
 *
 * <p>在 IDEA 中运行本类的 {@link #main(String[])}，或者在命令行执行 Spring Boot
 * 启动命令，都只会启动这一份 Java 程序。Spring 随后扫描
 * {@code com.example.ykdsummer} 及其子包，创建 Controller、Service、配置对象等 Bean。
 * 当 {@code ILinkBotService} 被创建完成后，它的 {@code @PostConstruct start()} 会自动执行，
 * 因此不需要另外再开一个 PowerShell 专门启动 iLink。</p>
 *
 * <p>所有 {@code @ConfigurationProperties} 类在此集中注册，不在各 Properties 类上使用
 * {@code @Component}，避免 Spring 创建重复 Bean。新增 Properties 类时需将其添加到
 * {@link EnableConfigurationProperties} 列表中。</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        AiProperties.class,
        AiTraceProperties.class,
        AiUsageProperties.class,
        ChatCompletionsConnectionProperties.class,
        ImageOpenAiClientProperties.class,
        ImageTaskExecutionProperties.class,
        OpenAiClientProperties.class,
        OssDocumentProperties.class,
        OssImageProperties.class,
        RealtimeEvidenceProperties.class,
        RagFlowProperties.class,
        WebSearchProperties.class,
        AdminWebProperties.class,
        ApiSecurityProperties.class,
        TokenEncryptionProperties.class,
        AliyunTtsProperties.class,
        FileProcessingProperties.class,
        ILinkProperties.class,
        ILinkRateLimitProperties.class,
        LongTextOutputProperties.class,
        TencentAsrProperties.class,
        VideoProcessingProperties.class,
        FashionAnalysisProperties.class,
        FashionCutoutProperties.class,
        FashionReferenceProperties.class,
        FashionSemanticProperties.class,
        FashionTryOnProperties.class,
        OutfitRecommendationProperties.class,
        PersistenceProperties.class,
        ReminderProperties.class,
        FileStorageProperties.class,
        WeatherProperties.class
})
public class YkdSummerApplication {

    /**
     * Java 进程的第一站。{@code args} 中的 {@code --ilink.enabled=true} 等参数会交给
     * Spring 解析，并覆盖 application.properties 中的同名配置。
     */
    public static void main(String[] args) {
        SpringApplication.run(YkdSummerApplication.class, args);
    }
}
