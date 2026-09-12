package com.intellidesk.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Component
public class ToolExecutorImpl implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutorImpl.class);

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final ToolResultSanitizer sanitizer;
    private final ThreadPoolTaskExecutor executor;
    private final int timeoutSeconds;

    public ToolExecutorImpl(ToolRegistry toolRegistry,
                            ObjectMapper objectMapper,
                            Validator validator,
                            ToolResultSanitizer sanitizer,
                            AgentProperties agentProperties,
                            @Qualifier("agentToolTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.sanitizer = sanitizer;
        this.executor = executor;
        this.timeoutSeconds = agentProperties.getToolTimeoutSeconds();
        log.info("ToolExecutorImpl initialized with timeoutSeconds={}", timeoutSeconds);
    }

    @Override
    public AgentToolExecutionResult execute(String toolName,
                                            Map<String, Object> rawArguments,
                                            ToolExecutionContext ctx) {
        // 1. Lookup tool
        AgentTool<?> tool = toolRegistry.get(toolName);
        if (tool == null) {
            return AgentToolExecutionResult.failure("TOOL_NOT_FOUND", "Unknown tool");
        }

        // 2. Defensive: argumentType must not be null
        Class<?> argType = tool.argumentType();
        if (argType == null) {
            return AgentToolExecutionResult.failure("TOOL_CONFIGURATION_ERROR", "Tool misconfigured");
        }

        // 3. Safe arguments
        Map<String, Object> safeArgs = rawArguments == null ? Map.of() : rawArguments;

        // 4. Convert arguments to typed DTO
        Object typedArgs;
        try {
            typedArgs = objectMapper.convertValue(safeArgs, argType);
        } catch (IllegalArgumentException e) {
            log.debug("Argument conversion failed for tool={}", toolName, e);
            return AgentToolExecutionResult.failure("ARGUMENT_TYPE_MISMATCH", "Invalid tool arguments");
        }

        // 5. Validate
        Set<ConstraintViolation<Object>> violations = validator.validate(typedArgs);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining("; "));
            return AgentToolExecutionResult.failure("ARGUMENT_VALIDATION_FAILED", "Validation failed: " + details);
        }

        // 6. Execute with timeout
        @SuppressWarnings("unchecked")
        AgentTool<Object> rawTool = (AgentTool<Object>) tool;
        Future<AgentToolExecutionResult> future;
        try {
            future = executor.submit(() -> rawTool.execute(ctx, typedArgs));
        } catch (RejectedExecutionException e) {
            log.warn("Tool executor busy for tool={}", toolName);
            return AgentToolExecutionResult.failure("TOOL_EXECUTOR_BUSY", "Tool executor is busy");
        }

        try {
            AgentToolExecutionResult result = future.get(timeoutSeconds, TimeUnit.SECONDS);

            // 7. Handle null result
            if (result == null) {
                return AgentToolExecutionResult.failure("TOOL_EXECUTION_ERROR", "Tool execution failed");
            }

            // 8. Sanitize all results
            if (result.success()) {
                String sanitized = sanitizer.sanitize(result.content());
                return new AgentToolExecutionResult(true, sanitized, null, null, result.metadata());
            }
            // Failure result: sanitize error message too
            String sanitizedMsg = sanitizer.sanitize(result.errorMessage());
            return new AgentToolExecutionResult(false, null, result.errorCode(), sanitizedMsg, result.metadata());

        } catch (TimeoutException e) {
            future.cancel(true);
            return AgentToolExecutionResult.failure("TOOL_TIMEOUT", "Tool execution timed out");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return AgentToolExecutionResult.failure("TOOL_EXECUTION_ERROR", "Tool execution interrupted");

        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ToolException te) {
                String sanitized = sanitizer.sanitize(te.getMessage());
                return AgentToolExecutionResult.failure(te.getErrorCode(), sanitized);
            }
            log.error("Tool execution failed for tool={}", toolName, cause);
            return AgentToolExecutionResult.failure("TOOL_EXECUTION_ERROR", "Tool execution failed");
        }
    }
}