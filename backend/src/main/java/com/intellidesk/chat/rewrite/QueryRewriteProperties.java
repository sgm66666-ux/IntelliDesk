package com.intellidesk.chat.rewrite;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "intellidesk.chat.rewrite")
public class QueryRewriteProperties {

    private boolean enabled = true;
    private int historyMessages = 4;
    private int maxLength = 500;
}