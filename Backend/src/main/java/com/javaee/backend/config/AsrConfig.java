package com.javaee.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "aliyun.asr")
@Data
public class AsrConfig {
    private String accessKeyId;
    private String accessKeySecret;
    private String appKey;
    private String format = "pcm";
    private Integer sampleRate = 16000;
}