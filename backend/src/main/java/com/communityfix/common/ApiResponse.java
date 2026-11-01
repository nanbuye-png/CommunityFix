package com.communityfix.common;

import org.slf4j.MDC;

public record ApiResponse<T>(int code, String message, T data, String requestId, String errorCode) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(200,"success",data,MDC.get("requestId"),null); }
    public static ApiResponse<Void> error(int code, String errorCode, String message) { return new ApiResponse<>(code,message,null,MDC.get("requestId"),errorCode); }
}
