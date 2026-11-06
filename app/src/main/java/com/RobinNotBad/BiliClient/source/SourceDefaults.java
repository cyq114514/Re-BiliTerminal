package com.RobinNotBad.BiliClient.source;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 内置默认数据源：让第三方源「开箱即用」，不必用户先找订阅地址。
 *
 * <p>内置的是 assets 里的快照，而不是启动时联网拉订阅：raw.githubusercontent.com 在部分网络下
 * 不可达，联网播种会把「开箱即用」变成「开箱等超时」；快照离线可用，代价只是新源要跟 App 版本更新
 * （{@link #SUBSCRIPTION_URL} 留给「导入订阅」手动更新）。
 *
 * <p>只自动播种一次：用户把源删空是明确意图，下次启动不再塞回来；要恢复走
 * 源管理页的「恢复默认源」（{@link #restoreDefaults()}）。
 */
public final class SourceDefaults {

    /** 内置快照对应的订阅地址（MajoSissi/animeko-source 的 online.json，导出格式） */
    public static final String SUBSCRIPTION_URL =
            "https://raw.githubusercontent.com/MajoSissi/animeko-source/main/dist/online.json";

    private static final String ASSET_PATH = "sources/default_subscription.json";

    /** 自动播种标记：置位后不再自动灌，避免用户主动删空后被反复填回 */
    private static final String KEY_SEEDED = "third_sources_seeded_v1";

    private static final String TAG = "third-defaults";

    private SourceDefaults() {
    }

    /**
     * 首次启动时播种内置源（后台线程调用）。
     *
     * <p>已有源时只补标记、不覆盖：用户自己导入的部分不能被内置快照顶掉。
     */
    public static void seedIfNeeded() {
        try {
            if (SharedPreferencesUtil.getBoolean(KEY_SEEDED, false)) return;
            int existing = SourceStore.count();
            if (existing > 0) {
                SharedPreferencesUtil.putBoolean(KEY_SEEDED, true);
                Logu.w(TAG, "已有 " + existing + " 个源，跳过内置播种");
                return;
            }
            SourceSubscriptions.Parsed parsed = parseSnapshot();
            if (!parsed.ok()) {
                //不置标记：快照异常（极少见）时下次启动再试一次，而不是永久放弃
                Logu.w(TAG, "内置快照不可用：" + parsed.error);
                return;
            }
            int added = SourceStore.upsert(parsed.sources);
            SharedPreferencesUtil.putBoolean(KEY_SEEDED, true);
            Logu.w(TAG, "内置源播种完成：新增 " + added + " 条 / " + parsed.sources.size()
                    + " 条来自 " + SUBSCRIPTION_URL);
        } catch (Throwable t) {
            Logu.w(TAG, "内置源播种失败：" + t.getClass().getSimpleName());
        }
    }

    /**
     * 手动「恢复默认源」：不管播种标记，直接合并一次（已存在的按 id 覆盖，不重置启停状态）。
     *
     * @return 新增条数；-1 表示快照不可用
     */
    public static int restoreDefaults() {
        try {
            SourceSubscriptions.Parsed parsed = parseSnapshot();
            if (!parsed.ok()) {
                Logu.w(TAG, "恢复默认源失败：" + parsed.error);
                return -1;
            }
            int added = SourceStore.upsert(parsed.sources);
            Logu.w(TAG, "恢复默认源：新增 " + added + " 条，共 " + parsed.sources.size() + " 条");
            return added;
        } catch (Throwable t) {
            Logu.w(TAG, "恢复默认源异常：" + t.getClass().getSimpleName());
            return -1;
        }
    }

    private static SourceSubscriptions.Parsed parseSnapshot() {
        return SourceSubscriptions.parse(readSnapshot());
    }

    /** 读 assets 快照；读不到返回空串（由 parse 给出「配置内容为空」） */
    private static String readSnapshot() {
        if (BiliTerminal.context == null) return "";
        try (InputStream in = BiliTerminal.context.getAssets().open(ASSET_PATH)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(192 * 1024);
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) > 0) out.write(buffer, 0, length);
            return out.toString("UTF-8");
        } catch (Exception e) {
            Logu.w(TAG, "读取内置快照失败：" + e.getClass().getSimpleName());
            return "";
        }
    }
}
