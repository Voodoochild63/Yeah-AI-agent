package com.yeah.yeahaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "serper")
public class SerperProperties {

    /**
     * Serper.dev API Key。空则跳过物理检索，只走私域库。
     */
    private String apiKey = "";

    private String baseUrl = "https://google.serper.dev";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
