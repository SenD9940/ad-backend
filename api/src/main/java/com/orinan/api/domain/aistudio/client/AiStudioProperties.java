package com.orinan.api.domain.aistudio.client;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.ai-studio.openai")
public class AiStudioProperties {
    private String apiKey = "";
    private String imageModel = "gpt-image-2.5-sunburst";
    private String textModel = "gpt-4o-mini";
    public boolean isConfigured() { return apiKey != null && !apiKey.isBlank() && imageModel != null && !imageModel.isBlank() && textModel != null && !textModel.isBlank(); }
}
