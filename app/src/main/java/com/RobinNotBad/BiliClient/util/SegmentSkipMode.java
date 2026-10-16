package com.RobinNotBad.BiliClient.util;

/**
 * 番剧片头/片尾片段跳过档位，对应设置页的五档选择。
 *
 * <p>与 PiliPlus 的「番剧片头/片尾跳过类型」语义一致（它同样提供总是跳过/跳过一次/手动跳过/仅显示/禁用
 * 五个选项，且与 UGC 的空降助手是两个互不影响的独立开关；本项目只做官方片头片尾这一支）。
 *
 * <p><b>持久化的是「选项下标」，不是 {@link #value}</b>：设置页的 choose 由
 * {@code SettingsAdapter.bindMulti} 用 {@code putInt(key, 下标)} 写入，读侧必须同样按下标解读
 * （{@link #fromIndex}）。当前五个成员的 value 恰好等于下标，但这是巧合而非契约——
 * 一旦插入或重排成员，按 value 反查就会读错档位。
 */
public enum SegmentSkipMode {
    /** 总是跳过：进入片段区间即跳到片段结束 */
    ALWAYS(0, "总是跳过"),
    /** 跳过一次：同一片段只自动跳一次，用户手动拖回去后不再跳 */
    ONCE(1, "跳过一次"),
    /** 手动跳过：不自动跳，片段区间内持续显示可点击的跳过按钮 */
    MANUAL(2, "手动跳过"),
    /** 仅显示：只提示片段存在，不做任何跳转 */
    SHOW_ONLY(3, "仅显示"),
    /** 禁用：完全不处理 */
    DISABLED(4, "禁用");

    /**
     * 默认档位，也是设置项 defaultValue 与读侧兜底的唯一来源。
     *
     * <p>默认选「手动跳过」而不是「总是跳过」：跳过是主动改变播放位置的行为，
     * 自动跳会在用户没有预期的情况下打断播放（片头区间与续播位置重叠时尤其明显），
     * 交给用户自己点一下更稳妥。
     */
    public static final SegmentSkipMode DEFAULT = MANUAL;

    public final int value;
    public final String label;

    SegmentSkipMode(int value, String label) {
        this.value = value;
        this.label = label;
    }

    /**
     * 按下标取档位（**读侧应使用的方法**，与设置页写入的下标同源）。
     * 越界或未设置时返回 {@link #DEFAULT}。
     */
    public static SegmentSkipMode fromIndex(int index) {
        SegmentSkipMode[] all = values();
        if (index < 0 || index >= all.length) return DEFAULT;
        return all[index];
    }

    /** 设置页用的默认下标 */
    public static int defaultIndex() {
        return DEFAULT.ordinal();
    }

    /**
     * 按 {@link #value} 字段取档位。仅在调用方明确持有 value 时使用；
     * 设置项读侧请用 {@link #fromIndex}。未知取值返回 {@link #DEFAULT}。
     */
    public static SegmentSkipMode fromValue(int value) {
        for (SegmentSkipMode m : values()) {
            if (m.value == value) return m;
        }
        return DEFAULT;
    }

    /** 设置页用的选项文案数组，顺序与声明顺序（即存储下标）一致 */
    public static String[] labels() {
        SegmentSkipMode[] all = values();
        String[] result = new String[all.length];
        for (int i = 0; i < all.length; i++) result[i] = all[i].label;
        return result;
    }
}
