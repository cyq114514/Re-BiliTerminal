package com.RobinNotBad.BiliClient.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.core.app.ApplicationProvider;

import com.RobinNotBad.BiliClient.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * 旧版播放器控制层布局（{@code activity_player_legacy}）的 JVM 结构冒烟。
 *
 * <p><b>为什么需要这个测试</b>：「旧版控制栏样式」方案的核心假设是
 * <b>两套布局的 id 集合与控件类型一致</b>（旧版仅少 {@code bottom_text_bar}），
 * {@code PlayerActivity} 才能用同一份 {@code findViewById}/控制代码跑两种布局。
 * 这个假设此前只靠人工 diff 保证：以后 1.4.x 再改 {@code activity_player.xml}
 * （比如新增锚点、把某个容器换成别的控件类型）时，旧样式用户机会直接
 * {@code ClassCastException} 或 NPE，而默认用户完全无感。这里把它固化成测试。
 *
 * <p><b>为什么不用 {@code @GraphicsMode(NATIVE)}</b>：仓库既有的
 * {@code PlayerLayoutInflationTest} 用 NATIVE 图形模式，在非 x86_64 开发机
 * （如 ARM64 PRoot/手机端）会因 Robolectric nativeruntime 加载失败而报
 * {@code UnsatisfiedLinkError}；本测试只验证布局结构与 id/类型，不验证绘制，
 * 走默认 LEGACY 图形模式即可跨架构稳定运行。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class PlayerLegacyLayoutInflationTest {

    private static final int NO_ID = View.NO_ID;

    private static Context noSwipe() {
        return new ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_NoSwipe);
    }

    private static View inflate(Context context, int layoutRes) {
        return LayoutInflater.from(context).inflate(layoutRes, null);
    }

    /** 收集布局里所有带 id 的 View：id -> 控件简单类名（只比类名，避免内部类/包名前缀差异） */
    private static Map<Integer, String> collectIds(View view, Map<Integer, String> out) {
        if (view == null) return out;
        int id = view.getId();
        if (id != NO_ID && !out.containsKey(id)) {
            out.put(id, view.getClass().getSimpleName());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectIds(group.getChildAt(i), out);
            }
        }
        return out;
    }

    @Test
    public void legacyLayoutInflatesUnderNoSwipeTheme() {
        View root = inflate(noSwipe(), R.layout.activity_player_legacy);
        assertNotNull(root);
        //回归焦点：这 8 个 id 都是 PlayerActivity 在 onCreate/initUI 里直接读的锚点
        assertNotNull(root.findViewById(R.id.root_layout));
        assertNotNull(root.findViewById(R.id.top));
        assertNotNull(root.findViewById(R.id.bottom_control));
        assertNotNull(root.findViewById(R.id.videoprogress));
        assertNotNull(root.findViewById(R.id.button_video));
        assertNotNull(root.findViewById(R.id.layout_speed));
        assertNotNull(root.findViewById(R.id.layout_volume));
        assertNotNull(root.findViewById(R.id.card_bg));
        assertNull("旧版布局不应包含 1.4 新增的 bottom_text_bar（Java 侧已判空）",
                root.findViewById(R.id.bottom_text_bar));
    }

    /**
     * 核心假设守卫：两套布局的 id 集合一致（旧版仅少 {@code bottom_text_bar}），
     * 且同 id 控件类型一致。任一断言失败都说明旧版布局已经和新版漂移。
     */
    @Test
    public void legacyAndCurrentLayoutsShareIdsAndTypes() {
        Map<Integer, String> current = collectIds(inflate(noSwipe(), R.layout.activity_player), new LinkedHashMap<>());
        Map<Integer, String> legacy = collectIds(inflate(noSwipe(), R.layout.activity_player_legacy), new LinkedHashMap<>());

        TreeSet<Integer> onlyInCurrent = new TreeSet<>(current.keySet());
        onlyInCurrent.removeAll(legacy.keySet());
        assertEquals("旧版布局只应缺少 bottom_text_bar，实际多出: " + names(onlyInCurrent),
                new TreeSet<>(Collections.singleton(R.id.bottom_text_bar)), onlyInCurrent);

        TreeSet<Integer> onlyInLegacy = new TreeSet<>(legacy.keySet());
        onlyInLegacy.removeAll(current.keySet());
        assertEquals("旧版布局不应包含新版没有的 id: " + names(onlyInLegacy),
                Collections.<Integer>emptySet(), onlyInLegacy);

        for (Map.Entry<Integer, String> entry : current.entrySet()) {
            String legacyType = legacy.get(entry.getKey());
            if (legacyType == null) continue;
            assertEquals("id " + name(entry.getKey()) + " 在两套布局里的控件类型不一致（会导致 findViewById 强转失败）",
                    entry.getValue(), legacyType);
        }
    }

    private String name(int id) {
        try {
            return ApplicationProvider.getApplicationContext().getResources().getResourceEntryName(id);
        } catch (Exception e) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private String names(TreeSet<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(name(id));
        }
        return sb.toString();
    }
}
