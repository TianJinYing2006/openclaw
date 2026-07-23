package com.example.ykdsummer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 整个 Spring Boot 项目的启动入口。
 *
 * <p>在 IDEA 中运行本类的 {@link #main(String[])}，或者在命令行执行 Spring Boot
 * 启动命令，都只会启动这一份 Java 程序。Spring 随后扫描
 * {@code com.example.ykdsummer} 及其子包，创建 Controller、Service、配置对象等 Bean。
 * 当 {@code ILinkBotService} 被创建完成后，它的 {@code @PostConstruct start()} 会自动执行，
 * 因此不需要另外再开一个 PowerShell 专门启动 iLink。</p>
 */
@SpringBootApplication
@EnableScheduling
public class YkdSummerApplication {

    /**
     * Java 进程的第一站。{@code args} 中的 {@code --ilink.enabled=true} 等参数会交给
     * Spring 解析，并覆盖 application.properties 中的同名配置。
     */
    public static void main(String[] args) {
        SpringApplication.run(YkdSummerApplication.class, args);
    }
}
