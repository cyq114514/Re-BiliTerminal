package com.RobinNotBad.BiliClient.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本名的语义化比较：应用内更新以此为唯一的"是否有新版本"判断依据。
 *
 * 动机：versionCode 采用构建日期语义且手工维护，历史上出现过三个版本共用同一个
 * versionCode 的事故（v1.1.1-fix / v1.1.2 / v1.1.3 均为 20261003）；而 versionName
 * 是每次发版必然变化、且面向用户展示的字段——用它可以彻底消除"忘记递增"这一类问题。
 *
 * 支持的格式：v 前缀可选；主.次.修订（次/修订可省略）；可选后缀 -fix / -fixN /
 * -BETAN / -RCN（大小写不敏感）。排序规则：
 *   1.1.3 < 1.1.4
 *   1.1.1 < 1.1.1-fix < 1.1.1-fix2 < 1.1.2     （修复版介于同版本与下一版之间）
 *   1.1.4-BETA1 < 1.1.4-BETA2 < 1.1.4          （预发布版早于正式版）
 *   1.1.10 > 1.1.4                              （数字感知，不是字典序）
 *
 * 编码为单个 long 便于比较：主×10^10 + 次×10^8 + 修订×10^5 + 类型×10^4 + 序号。
 * 类型：预发布(BETA/RC/PRE)=0，无后缀=1，修复(fix/hotfix)=2。
 * 解析失败（非法格式或未知后缀）返回 -1，调用方应退回 versionCode 比较。
 */
public final class VersionNameUtil {

    private static final Pattern PATTERN = Pattern.compile(
            "^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([A-Za-z]+)(\\d*))?$");

    private static final long TYPE_PRE_RELEASE = 0;
    private static final long TYPE_PLAIN = 1;
    private static final long TYPE_FIX = 2;

    private VersionNameUtil() {
    }

    /**返回可比较的编码值；解析失败返回 -1。*/
    public static long parse(String versionName) {
        if (versionName == null) return -1;
        String v = versionName.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        Matcher m = PATTERN.matcher(v);
        if (!m.matches()) return -1;

        long major = Long.parseLong(m.group(1));
        long minor = m.group(2) != null ? Long.parseLong(m.group(2)) : 0;
        long patch = m.group(3) != null ? Long.parseLong(m.group(3)) : 0;
        String qualifier = m.group(4);
        long num = m.group(5) != null && !m.group(5).isEmpty() ? Long.parseLong(m.group(5)) : 1;

        long type;
        if (qualifier == null) {
            type = TYPE_PLAIN;
            num = 0;
        } else {
            String q = qualifier.toLowerCase(Locale.ROOT);
            switch (q) {
                case "beta":
                case "rc":
                case "pre":
                    type = TYPE_PRE_RELEASE;
                    break;
                case "fix":
                case "hotfix":
                    type = TYPE_FIX;
                    break;
                default:
                    return -1;   //未知后缀不猜：交给调用方走 versionCode 兜底
            }
        }

        if (major > 99 || minor > 99 || patch > 999 || num > 9999) return -1;
        return major * 10_000_000_000L
                + minor * 100_000_000L
                + patch * 100_000L
                + type * 10_000L
                + num;
    }
}
