package com.planlock.common.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;

/**
 * JSON 数组元素数量在流式反序列化阶段超出上限时立即中止解析。
 */
public class JsonCollectionTooLargeException extends JsonMappingException {

    public JsonCollectionTooLargeException(JsonParser parser, int maxItems) {
        super(parser, "json array exceeds max items: " + maxItems);
    }
}
