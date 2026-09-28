package com.orinan.adminapi.domain.aistudio.client;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ai-studio.openai")
@Getter
@Setter
public class AdminAiAnalysisProperties {
    private String apiKey = "";
    private String textModel = "gpt-4o-mini";

    public boolean isConfigured() { return apiKey != null && !apiKey.isBlank() && textModel != null && !textModel.isBlank(); }
}
