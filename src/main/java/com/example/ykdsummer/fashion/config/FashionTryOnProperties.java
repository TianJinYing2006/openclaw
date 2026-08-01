package com.example.ykdsummer.fashion.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Provider deadline for background try-on work. It is intentionally separate from normal image edit timeout. */
@Component
@ConfigurationProperties(prefix = "app.fashion.tryon")
public class FashionTryOnProperties {
    private Duration providerTimeout = Duration.ofSeconds(150);

    public Duration getProviderTimeout() { return providerTimeout; }
    public void setProviderTimeout(Duration providerTimeout) {
        if (providerTimeout != null && !providerTimeout.isNegative() && !providerTimeout.isZero()) {
            this.providerTimeout = providerTimeout;
        }
    }
}
