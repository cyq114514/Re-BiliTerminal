/*
 * Bilibili Protobuf Danmaku Parser
 * 用于解析新版 protobuf 格式的弹幕数据
 */

package master.flame.danmaku.danmaku.parser.android;

import android.graphics.Color;

import java.util.List;

import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.android.DanmakuFactory;
import master.flame.danmaku.danmaku.model.android.Danmakus;
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser;
import master.flame.danmaku.danmaku.util.DanmakuUtils;

/**
 * Bilibili 新版 Protobuf 格式弹幕解析器
 */
public class BiliProtobufDanmakuParser extends BaseDanmakuParser {

    /**
     * 「空降弹幕」（含可跳转时间点的弹幕）的样式钩子。
     *
     * <p>为什么做成外部注入而不是在本模块里直接判断：时间戳解析与开关都在 app 侧
     * （{@code util/DanmakuTimestampUtil} + SharedPreferences），本模块是被 app 依赖的
     * 底层库，不能反向依赖 app。由调用方赋值即可，不需要时置 null。
     */
    public interface TimestampHighlighter {
        /** 文本是否含可跳转的时间点 */
        boolean isTimestamp(String text);

        /** 命中时的文字颜色（含 alpha） */
        int color();

        /** 命中时给文本加的前缀标识，空串表示不加 */
        String prefix();

        /** 命中时的字号放大倍数，1.0f 表示不变 */
        float sizeScale();
    }

    /** 由 app 侧注入；null 表示不标记 */
    public TimestampHighlighter timestampHighlighter;

    private float mDispScaleX;
    private float mDispScaleY;
    private List<?> mDanmakuSegments; // 存储 DmSegMobileReply 列表

    public BiliProtobufDanmakuParser() {
    }

    /**
     * 设置弹幕分段数据
     * 
     * @param segments DmSegMobileReply 列表
     */
    public void setDanmakuSegments(List<?> segments) {
        this.mDanmakuSegments = segments;
    }

    @Override
    public Danmakus parse() {
        if (mDanmakuSegments == null || mDanmakuSegments.isEmpty()) {
            return new Danmakus();
        }

        Danmakus result = new Danmakus();
        int index = 0;

        // 遍历所有分段
        for (Object segmentObj : mDanmakuSegments) {
            try {
                // 通过反射获取 elems 字段
                java.lang.reflect.Field elemsField = segmentObj.getClass().getField("elems");
                List<?> elems = (List<?>) elemsField.get(segmentObj);

                if (elems != null) {
                    // 遍历该分段中的所有弹幕
                    for (Object elemObj : elems) {
                        BaseDanmaku danmaku = parseDanmakuElem(elemObj, index++);
                        if (danmaku != null) {
                            result.addItem(danmaku);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return result;
    }

    /**
     * 解析单条弹幕元素
     * 
     * @param elemObj DanmakuElem 对象
     * @param index   弹幕索引
     * @return BaseDanmaku 对象
     */
    private BaseDanmaku parseDanmakuElem(Object elemObj, int index) {
        try {
            Class<?> elemClass = elemObj.getClass();

            int progress = elemClass.getField("progress").getInt(elemObj);
            int mode = elemClass.getField("mode").getInt(elemObj);
            int fontsize = elemClass.getField("fontsize").getInt(elemObj);
            int color = elemClass.getField("color").getInt(elemObj);
            String content = (String) elemClass.getField("content").get(elemObj);

            if (content == null || content.isEmpty()) {
                return null;
            }

            if (mode == 7 || mode == 8) {
                return null;
            }

            if (sharedPreferences != null && mode != 7 && mode != 8
                    && sharedPreferences.getBoolean("player_danmaku_forceR2L", false)) {
                mode = 1;
            }

            BaseDanmaku item = mContext.mDanmakuFactory.createDanmaku(mode, mContext);
            if (item != null) {
                item.time = progress;

                String displayContent = content;
                int displayColor = color | 0xFF000000;
                int displayShadow = displayColor <= Color.BLACK ? Color.WHITE : Color.BLACK;
                float sizeScale = 1f;

                //空降弹幕：含时间点的弹幕用高亮色 + 前缀标识区分出来，
                //否则用户根本不知道哪条能点（时间点解析与开关由 app 侧注入）
                TimestampHighlighter highlighter = timestampHighlighter;
                if (highlighter != null && highlighter.isTimestamp(content)) {
                    displayColor = highlighter.color();
                    //高亮色偏亮，阴影固定用黑色保证在浅色画面上也能看清
                    displayShadow = Color.BLACK;
                    sizeScale = highlighter.sizeScale();
                    String prefix = highlighter.prefix();
                    if (prefix != null && !prefix.isEmpty()) displayContent = prefix + content;
                }

                DanmakuUtils.fillText(item, displayContent);
                item.index = index;

                item.textSize = fontsize * (mDispDensity - 0.6f) * sizeScale;

                item.textColor = displayColor;
                item.textShadowColor = displayShadow;

                item.setTimer(mTimer);
            }

            return item;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public BaseDanmakuParser setDisplayer(IDisplayer disp) {
        super.setDisplayer(disp);
        mDispScaleX = mDispWidth / DanmakuFactory.BILI_PLAYER_WIDTH;
        mDispScaleY = mDispHeight / DanmakuFactory.BILI_PLAYER_HEIGHT;
        return this;
    }
}
