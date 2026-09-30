package com.RobinNotBad.BiliClient.util;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.style.ImageSpan;
import android.util.LruCache;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.model.Emote;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.FutureTarget;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


//表情包工具，用于将文本中的表情包替换为对应图片，部分代码来自catGPT
//2023-07-23

public class EmoteUtil {
    //单个表情的同步加载上限：评论解析线程会阻塞在这里，弱网下无界等待会把加载链路彻底卡死
    private static final long LOAD_TIMEOUT_SECONDS = 3L;
    //进程级 Drawable 复用：同一表情在一屏评论里可能出现多次，不去重的话每条评论都要重新解码
    private static final LruCache<String, Drawable> EMOTE_DRAWABLE_CACHE = new LruCache<>(96);

    public static SpannableStringBuilder textReplaceEmote(String text, JSONArray emote, float scale, Context context) throws JSONException, InterruptedException {
        SpannableStringBuilder result = new SpannableStringBuilder(text);
        if (emote != null && emote.length() > 0) {
            for (int i = 0; i < emote.length(); i++) {    //遍历每一个表情包
                JSONObject key = emote.getJSONObject(i);

                String name = key.getString("name");
                String emoteUrl = key.getString("url");
                int size = key.getInt("size");  //B站十分贴心的帮你把表情包大小都写好了，快说谢谢蜀黍

                replaceSingle(result, name, emoteUrl, size, scale, context);
            }
        }
        return result;
    }

    public static SpannableStringBuilder textReplaceEmote(String text, ArrayList<Emote> emotes, float scale, Context context, CharSequence source) {
        SpannableStringBuilder result = (source instanceof SpannableStringBuilder)
                ? (SpannableStringBuilder) source
                : new SpannableStringBuilder(text);

        if (emotes != null && !emotes.isEmpty()) {
            for (int i = 0; i < emotes.size(); i++) {    //遍历每一个表情包
                Emote key = emotes.get(i);

                String name = key.name;
                String emoteUrl = key.url;
                int size = key.size;  //B站十分贴心的帮你把表情包大小都写好了，快说谢谢蜀黍

                replaceSingle(result, name, emoteUrl, size, scale, context);
            }
        }
        return result;
    }

    public static SpannableStringBuilder textReplaceEmote(String text, ArrayList<Emote> emotes, float scale, Context context) {
        return textReplaceEmote(text, emotes, scale, context, null);
    }

    public static void replaceSingle(SpannableStringBuilder spannableString, String name, String url, int size, float scale, Context context) {
        try {
            int emotePx = (int) (size * ToolsUtil.sp2px(18) * scale);
            Drawable drawable = loadEmoteDrawable(context, url, emotePx);
            if (drawable == null) return;   //超时/失败跳过该表情，保留文本，不再无限阻塞解析

            String origText = spannableString.toString();

            int start = origText.indexOf(name);    //检测此字符串的起始位置
            while (start >= 0) {
                int end = start + name.length();    //计算得出结束位置
                ImageSpan imageSpan = new ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM);  //获得一个imagespan  这句不能放while上面，imagespan不可以复用，我也不知道为什么
                spannableString.setSpan(imageSpan, start, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);  //替换
                start = origText.indexOf(name, end);    //重新检测起始位置，直到找不到，然后开启下一个循环
            }
        } catch (Exception ignored) {
        }
    }

    public static void replaceSingle(SpannableStringBuilder spannableString, String url, int size, int start, int end, float scale) {
        try {
            int emotePx = (int) (size * ToolsUtil.sp2px(18) * scale);
            Drawable drawable = loadEmoteDrawable(BiliTerminal.context, url, emotePx);
            if (drawable == null) return;

            drawable.setBounds(0, 0, emotePx, emotePx);
            ImageSpan imageSpan = new ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM);
            spannableString.setSpan(imageSpan, start, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        } catch (Exception ignored) {
        }
    }

    /**
     * 同步取一个表情 Drawable：带超时、按"URL+显示尺寸"复用缓存。
     * 只允许在后台线程调用（评论/动态/私信解析均在后台），否则会阻塞主线程。
     *
     * @return 取不到（超时/网络失败）时返回 null
     */
    private static Drawable loadEmoteDrawable(Context context, String url, int sizePx) {
        if (url == null || url.isEmpty()) return null;
        String cacheKey = url + "|" + sizePx;
        Drawable cached = EMOTE_DRAWABLE_CACHE.get(cacheKey);
        if (cached != null) return cached;

        FutureTarget<Drawable> target = null;
        try {
            //显式给出目标尺寸：submit() 不带参数会按原图尺寸解码，浪费内存
            target = Glide.with(context).asDrawable().load(url).submit(sizePx, sizePx);
            Drawable drawable = target.get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            drawable.setBounds(0, 0, sizePx, sizePx);
            EMOTE_DRAWABLE_CACHE.put(cacheKey, drawable);
            return drawable;
        } catch (TimeoutException e) {
            if (target != null) Glide.with(context).clear(target);   //取消滞留请求，避免 FutureTarget 泄漏
            return null;
        } catch (Exception e) {
            if (target != null) Glide.with(context).clear(target);
            return null;
        }
    }

}
