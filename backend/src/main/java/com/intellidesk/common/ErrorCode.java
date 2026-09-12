package com.intellidesk.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    SUCCESS(0, "success", HttpStatus.OK),

    // 通用错误
    BAD_REQUEST(400, "请求参数错误", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(401, "未认证", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(403, "无权限", HttpStatus.FORBIDDEN),
    NOT_FOUND(404, "资源不存在", HttpStatus.NOT_FOUND),
    CONFLICT(409, "资源冲突", HttpStatus.CONFLICT),
    INTERNAL_ERROR(500, "服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR),

    // 认证错误
    USERNAME_OR_PASSWORD_ERROR(1001, "用户名或密码错误", HttpStatus.UNAUTHORIZED),
    USERNAME_ALREADY_EXISTS(1002, "用户名已存在", HttpStatus.CONFLICT),
    TOKEN_EXPIRED(1003, "Token 已过期", HttpStatus.UNAUTHORIZED),
    TOKEN_INVALID(1004, "Token 无效", HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_INVALID(1005, "Refresh Token 无效或已过期", HttpStatus.UNAUTHORIZED),
    USER_NOT_FOUND(1006, "用户不存在", HttpStatus.NOT_FOUND),
    USER_DISABLED(1007, "用户已被禁用", HttpStatus.FORBIDDEN),

    // 工作空间错误
    WORKSPACE_NOT_FOUND(2001, "工作空间不存在", HttpStatus.NOT_FOUND),
    WORKSPACE_ACCESS_DENIED(2002, "无权访问该工作空间", HttpStatus.FORBIDDEN),
    WORKSPACE_MEMBER_ALREADY_EXISTS(2003, "成员已存在", HttpStatus.CONFLICT),
    WORKSPACE_MEMBER_NOT_FOUND(2004, "成员不存在", HttpStatus.NOT_FOUND),
    WORKSPACE_CANNOT_REMOVE_OWNER(2005, "不能移除工作空间所有者", HttpStatus.BAD_REQUEST),
    WORKSPACE_NAME_EMPTY(2006, "工作空间名称不能为空", HttpStatus.BAD_REQUEST),
    WORKSPACE_NOT_EMPTY(2007, "工作空间下仍有知识库", HttpStatus.CONFLICT),

    // 知识库错误
    KNOWLEDGE_BASE_NOT_FOUND(3001, "知识库不存在", HttpStatus.NOT_FOUND),
    KNOWLEDGE_BASE_NAME_INVALID(3002, "知识库名称不合法", HttpStatus.BAD_REQUEST),
    KNOWLEDGE_BASE_NAME_ALREADY_EXISTS(3003, "知识库名称已存在", HttpStatus.CONFLICT),
    KNOWLEDGE_BASE_NOT_EMPTY(3004, "知识库下仍有文档", HttpStatus.CONFLICT),
    KNOWLEDGE_BASE_CHUNK_CONFIG_INVALID(3005, "Chunk 配置参数非法", HttpStatus.BAD_REQUEST),

    // 文档错误
    DOCUMENT_NOT_FOUND(4001, "文档不存在", HttpStatus.NOT_FOUND),
    DOCUMENT_TYPE_UNSUPPORTED(4002, "不支持的文档格式", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    DOCUMENT_EMPTY(4003, "文档内容为空", HttpStatus.BAD_REQUEST),
    DOCUMENT_TOO_LARGE(4004, "文档大小超过限制", HttpStatus.PAYLOAD_TOO_LARGE),
    DOCUMENT_DUPLICATE(4005, "文档已存在", HttpStatus.CONFLICT),
    DOCUMENT_UPLOAD_FAILED(4006, "文档上传失败", HttpStatus.SERVICE_UNAVAILABLE),
    DOCUMENT_INVALID_STATE(4007, "文档状态不允许该操作", HttpStatus.CONFLICT),
    DOCUMENT_CHUNK_NOT_AVAILABLE(4008, "文档 Chunk 暂不可用", HttpStatus.CONFLICT),
    DOCUMENT_DELETE_FAILED(4009, "文档删除失败", HttpStatus.SERVICE_UNAVAILABLE),
    DOCUMENT_RETRY_NOT_ALLOWED(4010, "文档不允许重试", HttpStatus.CONFLICT),
    DOCUMENT_PARSE_FAILED(4011, "文档解析失败", HttpStatus.UNPROCESSABLE_ENTITY),

    // 文档任务错误
    DOCUMENT_TASK_NOT_FOUND(4101, "文档任务不存在", HttpStatus.NOT_FOUND),
    DOCUMENT_TASK_DISPATCH_FAILED(4102, "文档任务派发失败", HttpStatus.SERVICE_UNAVAILABLE),

    // 嵌入错误
    EMBEDDING_DIMENSION_MISMATCH(5001, "嵌入向量维度不匹配", HttpStatus.INTERNAL_SERVER_ERROR),
    EMBEDDING_PROVIDER_ERROR(5002, "嵌入服务调用失败", HttpStatus.SERVICE_UNAVAILABLE),
    EMBEDDING_INVALID_OUTPUT(5003, "嵌入输出无效", HttpStatus.INTERNAL_SERVER_ERROR),
    EMBEDDING_EMPTY_INPUT(5004, "嵌入输入为空", HttpStatus.BAD_REQUEST),
    EMBEDDING_AUTH_ERROR(5005, "嵌入服务认证失败", HttpStatus.SERVICE_UNAVAILABLE),
    EMBEDDING_RATE_LIMIT(5006, "嵌入服务限流", HttpStatus.TOO_MANY_REQUESTS),

    // 检索错误
    RETRIEVAL_SCOPE_EMPTY(5101, "检索范围为空", HttpStatus.BAD_REQUEST),
    RETRIEVAL_NO_READY_DOCUMENT(5102, "无可检索的就绪文档", HttpStatus.NOT_FOUND),
    RETRIEVAL_INVALID_REQUEST(5103, "检索请求参数非法", HttpStatus.BAD_REQUEST),
    RETRIEVAL_NOT_READY(5104, "检索任务未就绪", HttpStatus.CONFLICT),
    RETRIEVAL_REINDEX_NOT_ALLOWED(5105, "当前状态不允许重建索引", HttpStatus.CONFLICT),
    RETRIEVAL_INDEXING_FAILED(5106, "检索索引处理失败", HttpStatus.UNPROCESSABLE_ENTITY),
    KEYWORD_SEARCH_UNAVAILABLE(5107, "关键词检索服务不可用", HttpStatus.SERVICE_UNAVAILABLE),
    RETRIEVAL_TASK_NOT_FOUND(5108, "检索任务不存在", HttpStatus.NOT_FOUND),
    VECTOR_SEARCH_UNAVAILABLE(5109, "向量检索服务不可用", HttpStatus.SERVICE_UNAVAILABLE),
    RERANK_UNAVAILABLE(5110, "重排序服务不可用", HttpStatus.SERVICE_UNAVAILABLE),

    // Chat 错误
    CHAT_CONVERSATION_NOT_FOUND(6001, "对话不存在", HttpStatus.NOT_FOUND),
    CHAT_MESSAGE_NOT_FOUND(6002, "消息不存在", HttpStatus.NOT_FOUND),
    CHAT_INVALID_REQUEST(6003, "对话请求参数非法", HttpStatus.BAD_REQUEST),
    CHAT_PROVIDER_ERROR(6004, "LLM 服务调用失败", HttpStatus.SERVICE_UNAVAILABLE),
    CHAT_AUTH_ERROR(6005, "LLM 服务认证失败", HttpStatus.SERVICE_UNAVAILABLE),
    CHAT_RATE_LIMITED(6006, "LLM 服务限流", HttpStatus.TOO_MANY_REQUESTS),
    CHAT_TIMEOUT(6007, "LLM 服务超时", HttpStatus.GATEWAY_TIMEOUT),
    CHAT_INVALID_RESPONSE(6008, "LLM 响应格式异常", HttpStatus.SERVICE_UNAVAILABLE),
    CHAT_CONVERSATION_BUSY(6009, "对话正在生成中，请等待", HttpStatus.CONFLICT),
    CHAT_STREAM_ERROR(6010, "流式对话异常", HttpStatus.SERVICE_UNAVAILABLE),

    // Agent 错误
    AGENT_MAX_STEPS_REACHED(6011, "Agent 步数超限", HttpStatus.UNPROCESSABLE_ENTITY),
    AGENT_TIMEOUT(6012, "Agent 执行超时", HttpStatus.GATEWAY_TIMEOUT),
    AGENT_TOOL_NOT_FOUND(6013, "Agent 工具不存在", HttpStatus.BAD_REQUEST),
    AGENT_LOOP_ERROR(6014, "Agent 循环异常", HttpStatus.INTERNAL_SERVER_ERROR),
    AGENT_INVALID_REQUEST(6015, "Agent 请求参数非法", HttpStatus.BAD_REQUEST),

    // API Key 错误
    API_KEY_NOT_FOUND(7001, "Key 不存在", HttpStatus.NOT_FOUND),
    API_KEY_NAME_INVALID(7002, "名称不合法", HttpStatus.BAD_REQUEST),
    API_KEY_EXPIRES_AT_INVALID(7003, "过期时间不合法", HttpStatus.BAD_REQUEST),
    API_KEY_SCOPE_INVALID(7004, "Scope 不合法", HttpStatus.BAD_REQUEST),
    API_KEY_INVALID(7005, "API Key 无效", HttpStatus.UNAUTHORIZED),
    API_KEY_SCOPE_DENIED(7006, "API Key 权限不足", HttpStatus.FORBIDDEN),
    API_KEY_CREDENTIAL_CONFLICT(7007, "认证冲突", HttpStatus.UNAUTHORIZED),

    // Rate Limit 错误
    RATE_LIMITED(8001, "请求过于频繁，请稍后重试", HttpStatus.TOO_MANY_REQUESTS);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}