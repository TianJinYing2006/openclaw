package com.example.ykdsummer.admin.config;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Adds an isolated localhost-only connector for the management site without moving the bot API off 8080. */
@Configuration
public class AdminWebConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.admin", name = "enabled", havingValue = "true")
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> adminConnector(AdminWebProperties properties) {
        return factory -> {
            Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            connector.setPort(properties.getPort());
            connector.setProperty("address", properties.getBindAddress());
            connector.setProperty("maxThreads", "32");
            connector.setProperty("acceptCount", "50");
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
