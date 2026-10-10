package com.planlock.common;

import lombok.Getter;

/**
 * 通用错误码（与 Obsidian/04-技术实现/接口设计.md 契约一致）。
 */
@Getter
public enum ErrorCode {

    OK(0, "ok"),
    UNAUTHORIZED(1001, "未认证"),
    INVALID_PARAM(1002, "参数错误"),
    NOT_FOUND(1003, "资源不存在"),
    VERSION_CONFLICT(1409, "版本冲突（其他设备有更改）"),
    INTERNAL_ERROR(2001, "内部错误");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
