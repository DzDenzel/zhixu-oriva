package com.planlock.common;

import com.planlock.common.web.JsonBodyLimitFilter;
import com.planlock.common.web.JsonCollectionTooLargeException;
import com.planlock.common.web.JsonPayloadTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Set;

/**
 * 全局异常处理：所有异常统一转换为显式 {@code application/json} {@link ApiResponse}，
 * 避免 {@code Accept: text/html} 触发二次内容协商失败回落为 HTML 错误页，且严禁将用户正文/请求载荷写入日志。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.debug("business error: code={}, path={}", e.getCode(), currentPath());
        return jsonResponse(HttpStatus.OK, e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null
                ? ErrorCode.INVALID_PARAM.getDefaultMessage()
                : fieldError.getField() + " " + fieldError.getDefaultMessage();
        log.debug("validation error: field={}, path={}", fieldError == null ? "unknown" : fieldError.getField(), currentPath());
        return jsonResponse(HttpStatus.OK, ErrorCode.INVALID_PARAM.getCode(), message);
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
        return jsonResponse(HttpStatus.OK, ErrorCode.INVALID_PARAM.getCode(), ErrorCode.INVALID_PARAM.getDefaultMessage());
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e, HttpServletRequest request) {
        boolean apiPath = isApiUri(request);
        log.debug("resource not found: apiPath={}, exception={}, path={}", apiPath, e.getClass().getSimpleName(), currentPath());
        if (apiPath) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(ApiResponse.error(ErrorCode.NOT_FOUND.getCode(), ErrorCode.NOT_FOUND.getDefaultMessage()));
        }
        return jsonResponse(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND.getCode(), ErrorCode.NOT_FOUND.getDefaultMessage());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        log.debug("media type not acceptable: path={}", currentPath());
        return jsonResponse(HttpStatus.NOT_ACCEPTABLE, ErrorCode.INVALID_PARAM.getCode(), "不支持请求的媒体类型");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.debug("method not supported: method={}, path={}", e.getMethod(), currentPath());
        HttpHeaders headers = new HttpHeaders();
        Set<HttpMethod> supported = e.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            headers.setAllow(supported);
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(ApiResponse.error(ErrorCode.INVALID_PARAM.getCode(), "不支持该请求方法"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        String causeClass = e.getCause() == null ? "none" : e.getCause().getClass().getName();
        log.error("unexpected error: category={}, exception={}, cause={}, path={}",
                classify(e), e.getClass().getName(), causeClass, currentPath());
        return jsonResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getDefaultMessage());
    }

    private static boolean isApiUri(HttpServletRequest request) {
        if (request == null || request.getRequestURI() == null) {
            return false;
        }
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        return uri.equals("/api") || uri.startsWith("/api/");
    }

    private static ResponseEntity<ApiResponse<Void>> jsonResponse(HttpStatus status, int code, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(ApiResponse.error(code, message));
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
