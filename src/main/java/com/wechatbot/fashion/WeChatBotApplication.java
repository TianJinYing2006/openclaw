package com.wechatbot.fashion;

import com.wechatbot.fashion.ai.config.AiProperties;
import com.wechatbot.fashion.ai.config.AiTraceProperties;
import com.wechatbot.fashion.ai.config.AiUsageProperties;
import com.wechatbot.fashion.ai.config.ChatCompletionsConnectionProperties;
import com.wechatbot.fashion.ai.config.ImageOpenAiClientProperties;
import com.wechatbot.fashion.ai.config.ImageTaskExecutionProperties;
import com.wechatbot.fashion.ai.config.OpenAiClientProperties;
import com.wechatbot.fashion.ai.config.OssDocumentProperties;
import com.wechatbot.fashion.ai.config.OssImageProperties;
import com.wechatbot.fashion.ai.config.RealtimeEvidenceProperties;
import com.wechatbot.fashion.ai.fashion.look.rag.FashionRagDiversityProperties;
import com.wechatbot.fashion.ai.fashion.look.rag.RagFlowProperties;
import com.wechatbot.fashion.ai.tool.WebSearchProperties;
import com.wechatbot.fashion.admin.config.AdminWebProperties;
import com.wechatbot.fashion.bot.config.AliyunTtsProperties;
import com.wechatbot.fashion.bot.config.FileProcessingProperties;
import com.wechatbot.fashion.bot.config.ILinkProperties;
import com.wechatbot.fashion.bot.config.ILinkRateLimitProperties;
import com.wechatbot.fashion.bot.config.LongTextOutputProperties;
import com.wechatbot.fashion.bot.config.TencentAsrProperties;
import com.wechatbot.fashion.bot.config.VideoProcessingProperties;
import com.wechatbot.fashion.common.security.ApiSecurityProperties;
import com.wechatbot.fashion.common.security.TokenEncryptionProperties;
import com.wechatbot.fashion.wardrobe.config.FashionAnalysisProperties;
import com.wechatbot.fashion.wardrobe.config.FashionCutoutProperties;
import com.wechatbot.fashion.wardrobe.config.FashionReferenceProperties;
import com.wechatbot.fashion.wardrobe.config.FashionSemanticProperties;
import com.wechatbot.fashion.wardrobe.config.FashionTryOnProperties;
import com.wechatbot.fashion.wardrobe.config.OutfitRecommendationProperties;
import com.wechatbot.fashion.persistence.PersistenceProperties;
import com.wechatbot.fashion.reminder.config.ReminderProperties;
import com.wechatbot.fashion.storage.FileStorageProperties;
import com.wechatbot.fashion.weather.WeatherProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 整个 Spring Boot 项目的启动入口。
 *
 * <p>在 IDEA 中运行本类的 {@link #main(String[])}，或者在命令行执行 Spring Boot
 * 启动命令，都只会启动这一份 Java 程序。Spring 随后扫描
 * {@code com.wechatbot.fashion} 及其子包，创建 Controller、Service、配置对象等 Bean。
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
        FashionRagDiversityProperties.class,
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
public class WeChatBotApplication {

    /**
     * Java 进程的第一站。{@code args} 中的 {@code --ilink.enabled=true} 等参数会交给
     * Spring 解析，并覆盖 application.properties 中的同名配置。
     */
    public static void main(String[] args) {
        SpringApplication.run(WeChatBotApplication.class, args);
    }
}
