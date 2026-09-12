package dev.liquidpanel.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * JSON 工具类。
 *
 * <p>把 Gson 包在这里，其他类不直接依赖 Gson，日后换实现只改这一个文件。
 * 面板的 account.json 与前后端接口都用它。
 */
public final class JsonUtil {

    /** 带缩进，用于写磁盘文件 */
    private static final Gson PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** 紧凑格式，用于网络传输 */
    private static final Gson COMPACT = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private JsonUtil() {
    }

    public static String toPrettyJson(Object object) {
        return PRETTY.toJson(object);
    }

    public static String toJson(Object object) {
        return COMPACT.toJson(object);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return COMPACT.fromJson(json, type);
    }

    /**
     * 解析成 JsonObject，格式非法时返回 null。
     */
    public static JsonObject parseObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 取值：任何异常都退化成默认值，避免因为一个字段格式不对就把请求打挂
    // ------------------------------------------------------------------

    public static String optString(JsonObject object, String key, String defaultValue) {
        if (object == null) {
            return defaultValue;
        }
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return defaultValue;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : defaultValue;
    }

    public static int optInt(JsonObject object, String key, int defaultValue) {
        if (object == null) {
            return defaultValue;
        }
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    public static boolean optBoolean(JsonObject object, String key, boolean defaultValue) {
        if (object == null) {
            return defaultValue;
        }
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }
}
