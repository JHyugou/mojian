package com.mojian.common.api;

/**
 * 统一业务错误码。0 表示成功，4 段为客户端错误，5 段为服务端错误。
 */
public enum ErrorCode {

    SUCCESS(0, "成功"),

    PARAM_INVALID(40000, "参数不合法"),
    NOT_FOUND(40400, "资源不存在"),
    CONFLICT(40900, "请求冲突"),

    SYSTEM_ERROR(50000, "系统内部错误"),
    LLM_CONFIG_MISSING(50001, "模型服务未配置，请检查服务端环境变量"),
    LLM_REQUEST_FAILED(50002, "模型请求失败，请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
