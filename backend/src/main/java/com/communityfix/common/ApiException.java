package com.communityfix.common;

public class ApiException extends RuntimeException {
    public final int status;
    public final String errorCode;
    public ApiException(int status, String errorCode, String message) { super(message); this.status = status; this.errorCode = errorCode; }
    public static ApiException bad(String message) { return new ApiException(400,"INVALID_INPUT",message); }
    public static ApiException forbidden() { return new ApiException(403,"FORBIDDEN","没有此操作的权限"); }
    public static ApiException missing() { return new ApiException(404,"NOT_FOUND","记录不存在或不可访问"); }
    public static ApiException conflict() { return new ApiException(409,"ORDER_STATE_CONFLICT","记录已变化，请刷新后重试"); }
}
