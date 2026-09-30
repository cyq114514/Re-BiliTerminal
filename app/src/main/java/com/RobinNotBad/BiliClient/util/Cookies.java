package com.RobinNotBad.BiliClient.util;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

public class Cookies {
    private final Map<String, String> cookieMap = new HashMap<>();

    public Cookies(String cookieString) {
        parseCookieString(cookieString);
    }

    private void parseCookieString(String cookieString) {
        cookieMap.clear();
        String[] cookies = cookieString.split("; ");
        for (String cookie : cookies) {
            //按首个等号切分：值本身可能含有等号（如base64填充），也可能为空串
            int index = cookie.indexOf('=');
            if (index > 0) {
                cookieMap.put(cookie.substring(0, index), cookie.substring(index + 1));
            }
        }
    }

    public void set(String key, String value) {
        //value 为 null 视为删除该键：否则 toString 会拼出 "key=null" 字面量污染 Cookie 串
        if (value == null) {
            cookieMap.remove(key);
            return;
        }
        cookieMap.put(key, value);
    }

    public String get(String key) {
        return cookieMap.get(key);
    }

    public String getOrDefault(String key, String defaultVal) {
        String val = cookieMap.get(key);
        return val != null ? val : defaultVal;
    }

    public boolean containsKey(String key) {
        return cookieMap.containsKey(key);
    }

    @NonNull
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookieMap.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
        }
        return sb.toString();
    }

}
