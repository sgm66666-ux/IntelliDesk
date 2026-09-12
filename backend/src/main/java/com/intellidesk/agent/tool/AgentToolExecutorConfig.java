package com.intellidesk.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AgentToolExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentToolExecutorConfig.class);

    @Bean(name = "agentToolTaskExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor agentToolTaskExecutor(AgentProperties agentProperties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(agentProperties.getToolCorePoolSize());
        executor.setMaxPoolSize(agentProperties.getToolMaxPoolSize());
        executor.setQueueCapacity(agentProperties.getToolQueueCapacity());
        executor.setThreadNamePrefix("agent-tool-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        log.info("AgentToolTaskExecutor initialized: core={}, max={}, queue={}",
                agentProperties.getToolCorePoolSize(),
                agentProperties.getToolMaxPoolSize(),
                agentProperties.getToolQueueCapacity());
        return executor;
    }
}