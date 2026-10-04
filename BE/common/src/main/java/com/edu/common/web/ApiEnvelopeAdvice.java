package com.edu.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * Wraps every JSON response into the envelope the FE expects
 * ({success, statusCode, message, data, path, timestamp, responseTime}).
 * Replaces the gateway's ResponseWrapperFilter.
 */
@RestControllerAdvice
public class ApiEnvelopeAdvice implements ResponseBodyAdvice<Object> {

    private static final String ERROR_URI_ATTR = "jakarta.servlet.error.request_uri";

    private final ObjectMapper objectMapper;

    public ApiEnvelopeAdvice(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // StringHttpMessageConverter can only write String, a wrapped Map would throw ClassCastException.
        return !StringHttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        // Gateway only wrapped responses that actually had a JSON body; Void/empty ones passed through.
        if (body == null || selectedContentType == null || !selectedContentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
            return body;
        }
        if (!(request instanceof ServletServerHttpRequest servletRequest)
                || !(response instanceof ServletServerHttpResponse servletResponse)) {
            return body;
        }

        HttpServletRequest httpRequest = servletRequest.getServletRequest();
        HttpServletResponse httpResponse = servletResponse.getServletResponse();

        Object errorUri = httpRequest.getAttribute(ERROR_URI_ATTR);
        String path = errorUri != null ? errorUri.toString() : httpRequest.getRequestURI();
        if (path.startsWith("/actuator")) {
            return body;
        }

        int status = httpResponse.getStatus();
        boolean success = status < 400;

        Object startAttr = httpRequest.getAttribute(RequestTimingFilter.START_TIME_ATTR);
        long elapsed = startAttr instanceof Long start ? System.currentTimeMillis() - start : 0;

        JsonNode original = objectMapper.valueToTree(body);
        String message = messageOf(original, success ? "OK" : "Đã có lỗi xảy ra");

        ObjectNode wrapped = objectMapper.createObjectNode();
        wrapped.put("success", success);
        wrapped.put("statusCode", status);
        wrapped.put("message", message);
        if (success) {
            wrapped.set("data", original.has("data") ? original.get("data") : original);
        } else {
            wrapped.putNull("data");
        }
        wrapped.put("path", path);
        wrapped.put("timestamp", Instant.now().toString());
        wrapped.put("responseTime", elapsed + " ms");
        return wrapped;
    }

    /** Jackson 2's asText(default) fell back for null nodes; Jackson 3's asString(default) returns "". */
    private static String messageOf(JsonNode original, String fallback) {
        JsonNode m = original.path("message");
        return m.isMissingNode() || m.isNull() ? fallback : m.asString(fallback);
    }
}
