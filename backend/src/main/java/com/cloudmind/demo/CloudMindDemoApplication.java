package com.cloudmind.demo;

import com.cloudmind.demo.config.MinioProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(MinioProperties.class)
@EnableScheduling
public class CloudMindDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(CloudMindDemoApplication.class, args);
    }
}
