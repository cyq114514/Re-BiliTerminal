package com.RobinNotBad.BiliClient.util;

import com.RobinNotBad.BiliClient.model.LiveRoom;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;

public class GsonUtil {

    private static final Gson GSON = new GsonBuilder()
            .setLenient()
            .serializeNulls()
            .registerTypeAdapter(int.class, new IntOrStringAdapter())
            .registerTypeAdapter(Integer.class, new IntOrStringAdapter())
            .registerTypeAdapter(long.class, new LongOrStringAdapter())
            .registerTypeAdapter(Long.class, new LongOrStringAdapter())
            .registerTypeAdapter(LiveRoom.class, new LiveRoomDeserializer())
            .create();

    public static Gson getGson() {
        return GSON;
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) return null;
        try {
            return GSON.fromJson(json, clazz);
        } catch (Exception e) {
            Logu.w("GsonUtil", "fromJson failed: " + e.getMessage());
            return null;
        }
    }

    public static <T> T fromJson(String json, Type type) {
        if (json == null || json.isEmpty()) return null;
        try {
            return GSON.fromJson(json, type);
        } catch (Exception e) {
            Logu.w("GsonUtil", "fromJson failed: " + e.getMessage());
            return null;
        }
    }

    public static <T> List<T> fromJsonList(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) return null;
        try {
            Type type = TypeToken.getParameterized(List.class, clazz).getType();
            return GSON.fromJson(json, type);
        } catch (Exception e) {
            Logu.w("GsonUtil", "fromJsonList failed: " + e.getMessage());
            return null;
        }
    }

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    private static class IntOrStringAdapter implements JsonDeserializer<Integer>, JsonSerializer<Integer> {
        @Override
        public Integer deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) return 0;
            if (json.isJsonPrimitive()) {
                JsonPrimitive prim = json.getAsJsonPrimitive();
                if (prim.isNumber()) return prim.getAsInt();
                if (prim.isString()) {
                    try { return Integer.parseInt(prim.getAsString()); } catch (NumberFormatException e) { return 0; }
                }
                if (prim.isBoolean()) return prim.getAsBoolean() ? 1 : 0;
            }
            return 0;
        }

        @Override
        public JsonElement serialize(Integer src, Type typeOfSrc, com.google.gson.JsonSerializationContext context) {
            return new JsonPrimitive(src);
        }
    }

    private static class LongOrStringAdapter implements JsonDeserializer<Long>, JsonSerializer<Long> {
        @Override
        public Long deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) return 0L;
            if (json.isJsonPrimitive()) {
                JsonPrimitive prim = json.getAsJsonPrimitive();
                if (prim.isNumber()) return prim.getAsLong();
                if (prim.isString()) {
                    try { return Long.parseLong(prim.getAsString()); } catch (NumberFormatException e) { return 0L; }
                }
                if (prim.isBoolean()) return prim.getAsBoolean() ? 1L : 0L;
            }
            return 0L;
        }

        @Override
        public JsonElement serialize(Long src, Type typeOfSrc, com.google.gson.JsonSerializationContext context) {
            return new JsonPrimitive(src);
        }
    }

    private static class LiveRoomDeserializer implements JsonDeserializer<LiveRoom> {
        private static final Gson INNER_GSON = new GsonBuilder()
                .setLenient()
                .registerTypeAdapter(int.class, new IntOrStringAdapter())
                .registerTypeAdapter(Integer.class, new IntOrStringAdapter())
                .registerTypeAdapter(long.class, new LongOrStringAdapter())
                .registerTypeAdapter(Long.class, new LongOrStringAdapter())
                .create();

        @Override
        public LiveRoom deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || !json.isJsonObject()) return null;
            JsonObject obj = json.getAsJsonObject();

            if (obj.has("verify") && obj.get("verify").isJsonPrimitive()) {
                obj.remove("verify");
            }

            return INNER_GSON.fromJson(obj, LiveRoom.class);
        }
    }
}
