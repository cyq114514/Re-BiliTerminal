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

                DanmakuUtils.fillText(item, content);
                item.index = index;

                item.textSize = fontsize * (mDispDensity - 0.6f);

                item.textColor = color | 0xFF000000;
                item.textShadowColor = (color | 0xFF000000) <= Color.BLACK ? Color.WHITE : Color.BLACK;

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
