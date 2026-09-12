package com.RobinNotBad.BiliClient.util;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;

/*
2023-12-22 RobinNotBad
这玩意是可以用来快速拆解的，但也许并没有什么用
哎你别说这玩意还真能从一个html里直接拎出来json，在opus里还怪方便的  //2025-07-13
 */

public class JsonUtil {

    public static String search(JSONObject input, String name, String defaultValue) {
        return search(input.toString(), name, defaultValue);
    }

    public static String search(String input, String name, String defaultValue) {
        if (name == null || name.isEmpty() || input == null) return defaultValue;

        String searchKey = "\"" + name + "\":";
        int index = input.indexOf(searchKey);   // "name":
        if (index == -1) return defaultValue;

        int count = 0;
        int i = index + searchKey.length();
        for (int j = i; j < input.length(); j++) {
            char thisChar = input.charAt(j);
            if (thisChar == '{' || thisChar == '[') count++;
            if (thisChar == '}' || thisChar == ']') count--;

            if (count == 0) {
                char nextChar = (j + 1 < input.length()) ? input.charAt(j + 1) : ',';
                if (nextChar == ',' || nextChar == '}' || nextChar == ']' || j + 1 >= input.length()) {
                    if (input.charAt(i) == '\"') {
                        return StringUtil.unEscape(input.substring(i + 1, j));
                    } else return input.substring(i, j + 1);
                }
            }
        }
        return defaultValue;
    }

    public static ArrayList<String> jsonToArrayList(JSONArray jsonArray, boolean reverse) throws JSONException {
        ArrayList<String> arrayList = new ArrayList<>();
        if (reverse) {
            for (int i = jsonArray.length() - 1; i >= 0; i--) {
                arrayList.add((String) jsonArray.get(i));
            }
        } else {
            for (int i = 0; i < jsonArray.length(); i++) {
                arrayList.add((String) jsonArray.get(i));
            }
        }
        return arrayList;
    }

    /*
     * json列项函数 用于表情包，自己写的以替换luern的库
     * 我当时为什么还要手写这个的解析？明明有现成的不查一下……
     */
    public static ArrayList<String> getJsonKeys(JSONObject jsonObject) {
        ArrayList<String> list = new ArrayList<>();
        for (Iterator<String> it = jsonObject.keys(); it.hasNext(); ) {
            String s = it.next();
            list.add(s);
        }
        return list;
    }

}







