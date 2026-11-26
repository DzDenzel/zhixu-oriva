package com.planlock.common.web;

import java.io.IOException;

/**
 * 请求体字节数超出安全上限时抛出，用于在反序列化之前中断读取。
 */
public class JsonPayloadTooLargeException extends IOException {

    public JsonPayloadTooLargeException() {
        super("json payload exceeds limit");
    }
}
