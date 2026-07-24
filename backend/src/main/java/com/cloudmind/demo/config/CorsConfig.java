package com.cloudmind.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.util.Arrays;

@Configuration
public class CorsConfig implements WebMvcConfigurer {
    private final String[] allowedOrigins;

    public CorsConfig(
            @Value("${cloudmind.security.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
            String allowedOrigins
    ) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .peek(origin -> {
                    if ("*".equals(origin) || origin.contains("*")) {
                        throw new IllegalStateException("CORS 白名单不允许使用通配符");
                    }
                    URI uri;
                    try {
                        uri = URI.create(origin);
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalStateException("CORS 白名单 Origin 格式无效", exception);
                    }
                    boolean httpOrigin = "http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme());
                    if (!httpOrigin
                            || uri.getHost() == null
                            || uri.getHost().isBlank()
                            || uri.getRawUserInfo() != null
                            || uri.getRawQuery() != null
                            || uri.getRawFragment() != null
                            || uri.getPath() != null && !uri.getPath().isBlank()
                            || uri.getPort() == 0
                            || uri.getPort() < -1
                            || uri.getPort() > 65535) {
                        throw new IllegalStateException("CORS 白名单必须是明确的 http(s) Origin");
                    }
                })
                .distinct()
                .toArray(String[]::new);
        if (this.allowedOrigins.length == 0) {
            throw new IllegalStateException("至少需要配置一个 CORS Origin");
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders(
                        "Authorization",
                        "Content-Type",
                        "X-Token",
                        "X-Request-Id"
                )
                .exposedHeaders(
                        "Content-Disposition",
                        "X-Request-Id",
                        "Retry-After"
                )
                .allowCredentials(false)
                .maxAge(3600);
    }
}
