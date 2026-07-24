package com.cloudmind.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;

@Component
@Profile("prod")
public class ProductionSecurityValidator {
    public ProductionSecurityValidator(
            @Value("${cloudmind.security.allowed-origins}") String allowedOrigins
    ) {
        Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .forEach(this::validateOrigin);
    }

    private void validateOrigin(String origin) {
        URI uri;
        try {
            uri = URI.create(origin);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("生产 CORS Origin 格式无效", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getPort() == 80
                || uri.getPort() == 0
                || uri.getPort() < -1
                || uri.getPort() > 65535
                || uri.getPath() != null && !uri.getPath().isBlank()) {
            throw new IllegalStateException("生产 CORS Origin 必须是无路径的 HTTPS 域名");
        }
    }
}
