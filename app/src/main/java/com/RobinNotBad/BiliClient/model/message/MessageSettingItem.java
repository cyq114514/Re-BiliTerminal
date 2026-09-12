package com.RobinNotBad.BiliClient.model.message;

public class MessageSettingItem {
    public static final int TYPE_SWITCH = 0;
    public static final int TYPE_CHOOSE = 1;

    public String key;
    public String title;
    public String desc;
    public int type;
    public boolean value;
    public String[] options;

    public MessageSettingItem(String key, String title, String desc, int type, boolean value, String[] options) {
        this.key = key;
        this.title = title;
        this.desc = desc;
        this.type = type;
        this.value = value;
        this.options = options;
    }
}
