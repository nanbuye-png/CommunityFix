package com.communityfix.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ExceptionAdvice {
    private static final Logger log = LoggerFactory.getLogger(ExceptionAdvice.class);
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> business(ApiException ex) { return ResponseEntity.status(ex.status).body(ApiResponse.error(ex.status,ex.errorCode,ex.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class, org.springframework.http.converter.HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class, org.springframework.web.bind.MissingRequestHeaderException.class, org.springframework.web.bind.MissingServletRequestParameterException.class, IllegalArgumentException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<?> input(Exception ex) { return ResponseEntity.badRequest().body(ApiResponse.error(400,"INVALID_INPUT","请检查填写内容、日期或文件大小")); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> duplicate(Exception ex) { return ResponseEntity.status(409).body(ApiResponse.error(409,"DATA_CONFLICT","记录重复或关联数据已变化")); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception ex) { log.error("Request failed",ex); return ResponseEntity.internalServerError().body(ApiResponse.error(500,"INTERNAL_ERROR","服务暂时不可用，请稍后重试")); }
}
