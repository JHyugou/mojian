package com.mojian.common.exception;

import com.mojian.common.api.ErrorCode;
import lombok.Getter;

/**
 * 业务异常。由全局异常处理器统一转换为 ApiResponse，
 * 面向前端展示 message，不携带服务端堆栈。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
