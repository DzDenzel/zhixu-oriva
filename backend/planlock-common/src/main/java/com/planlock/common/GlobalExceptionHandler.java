package com.planlock.common;

import com.planlock.common.web.JsonBodyLimitFilter;
import com.planlock.common.web.JsonCollectionTooLargeException;
import com.planlock.common.web.JsonPayloadTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理：所有异常统一转换为 {@link ApiResponse}，且严禁将用户正文/请求载荷写入日志。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.debug("business error: code={}, path={}", e.getCode(), currentPath());
        return ResponseEntity.ok(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null
                ? ErrorCode.INVALID_PARAM.getDefaultMessage()
                : fieldError.getField() + " " + fieldError.getDefaultMessage();
        log.debug("validation error: field={}, path={}", fieldError == null ? "unknown" : fieldError.getField(), currentPath());
        return ResponseEntity.ok(ApiResponse.error(ErrorCode.INVALID_PARAM.getCode(), message));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            JsonPayloadTooLargeException.class,
            JsonCollectionTooLargeException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadPayload(Exception e) {
        String category = JsonBodyLimitFilter.hasPayloadLimitCause(e) ? "payload_limit" : "bad_payload";
        log.warn("malformed request: category={}, exception={}, path={}", category, e.getClass().getSimpleName(), currentPath());
        return ResponseEntity.ok(ApiResponse.error(ErrorCode.INVALID_PARAM.getCode(), ErrorCode.INVALID_PARAM.getDefaultMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        String causeClass = e.getCause() == null ? "none" : e.getCause().getClass().getName();
        log.error("unexpected error: category={}, exception={}, cause={}, path={}",
                classify(e), e.getClass().getName(), causeClass, currentPath());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getDefaultMessage()));
    }

    private static String classify(Exception e) {
        String name = e.getClass().getName();
        if (e instanceof java.sql.SQLException || name.startsWith("org.springframework.dao.") || name.startsWith("org.apache.ibatis.")) {
            return "persistence";
        }
        return "unexpected";
    }

    private static String currentPath() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            return req.getMethod() + " " + req.getRequestURI();
        }
        return "unknown";
    }
}
