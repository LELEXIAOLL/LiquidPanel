package dev.liquidpanel.panels.http;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 前后端统一的响应体：{@code {success, message, data}}。
 *
 * <p>所有接口都用它，前端只需要判断 success 一个字段，不用为每个接口写一套解析逻辑。
 */
public final class ApiResponse {

    private final boolean success;
    private final String message;
    private final Object data;

    private ApiResponse(boolean success, String message, Object data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static ApiResponse ok() {
        return new ApiResponse(true, null, null);
    }

    public static ApiResponse ok(Object data) {
        return new ApiResponse(true, null, data);
    }

    public static ApiResponse ok(String message, Object data) {
        return new ApiResponse(true, message, data);
    }

    public static ApiResponse error(String message) {
        return new ApiResponse(false, message, null);
    }

    /**
     * 转成可以被 JsonUtil 序列化的 Map，字段名固定为小写，前端好读。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>(4);
        map.put("success", success);
        if (message != null) {
            map.put("message", message);
        }
        if (data != null) {
            map.put("data", data);
        }
        return map;
    }
}
