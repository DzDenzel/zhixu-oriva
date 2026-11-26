package com.planlock.common.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 流式限长 List 反序列化器：读取到第 {@code maxItems + 1} 个元素起止 token 时立即中止解析。
 */
public class BoundedListDeserializer extends JsonDeserializer<List<?>> implements ContextualDeserializer {

    public static final int DEFAULT_MAX_ITEMS = 500;

    private final JavaType elementType;
    private final JsonDeserializer<Object> elementDeserializer;
    private final int maxItems;

    public BoundedListDeserializer() {
        this(null, null, DEFAULT_MAX_ITEMS);
    }

    private BoundedListDeserializer(JavaType elementType, JsonDeserializer<Object> elementDeserializer, int maxItems) {
        this.elementType = elementType;
        this.elementDeserializer = elementDeserializer;
        this.maxItems = maxItems;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property)
            throws JsonMappingException {
        int limit = DEFAULT_MAX_ITEMS;
        JavaType containerType = null;
        if (property != null) {
            BoundedList annotation = property.getAnnotation(BoundedList.class);
            if (annotation != null && annotation.max() > 0) {
                limit = annotation.max();
            }
            containerType = property.getType();
        } else {
            containerType = ctxt.getContextualType();
        }
        JavaType contentType = containerType != null && containerType.getContentType() != null
                ? containerType.getContentType()
                : ctxt.constructType(Object.class);
        JsonDeserializer<Object> child = ctxt.findContextualValueDeserializer(contentType, property);
        return new BoundedListDeserializer(contentType, child, limit);
    }

    @Override
    public List<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }
        if (!p.isExpectedStartArrayToken()) {
            return (List<?>) ctxt.handleUnexpectedToken(List.class, p);
        }
        List<Object> result = new ArrayList<>();
        JsonToken token;
        while ((token = p.nextToken()) != JsonToken.END_ARRAY) {
            if (result.size() >= maxItems) {
                throw new JsonCollectionTooLargeException(p, maxItems);
            }
            if (token == JsonToken.VALUE_NULL) {
                result.add(null);
            } else if (elementDeserializer != null) {
                result.add(elementDeserializer.deserialize(p, ctxt));
            } else if (elementType != null) {
                result.add(ctxt.readValue(p, elementType));
            } else {
                result.add(p.readValueAs(Object.class));
            }
        }
        return result;
    }
}
