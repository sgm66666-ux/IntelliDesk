package com.intellidesk.api_key;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "intellidesk.api-key")
public class ApiKeyProperties {

    private int prefixLength = 8;

    private int secretBytes = 32;

    private int maxPrefixRetries = 5;

    public int getPrefixLength() {
        return prefixLength;
    }

    public void setPrefixLength(int prefixLength) {
        this.prefixLength = prefixLength;
    }

    public int getSecretBytes() {
        return secretBytes;
    }

    public void setSecretBytes(int secretBytes) {
        this.secretBytes = secretBytes;
    }

    public int getMaxPrefixRetries() {
        return maxPrefixRetries;
    }

    public void setMaxPrefixRetries(int maxPrefixRetries) {
        this.maxPrefixRetries = maxPrefixRetries;
    }
}