package com.RobinNotBad.BiliClient.util;

import android.text.TextUtils;

import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 B 站消息里的 {@code uri} / {@code native_uri} 解析成可跳转的目标。
 *
 * <p>为什么单独抽出来：原实现只认 {@code /video/BV}、{@code t.bilibili.com/}、{@code bilibili://opus/}
 * 三种形态，而"回复我的"消息实际给的是
 * {@code https://www.bilibili.com/opus/<dynId>#reply<rpid>}——{@code /opus/} 没有规则命中，
 * 于是回退到 {@code subject_id}。但实测（20 条真实消息）{@code business_id=11} 时
 * subject_id 是<b>评论区的 oid</b>（如 413634671），不是动态 id，拿它去查动态必然查不到：
 * 服务端返回 {@code DYNAMIC_TYPE_NONE} 空壳 → comment_id=0 → 评论接口 {@code invalid oid}。
 *
 * <p>规则参照 PiliPlus 的 {@code app_scheme.dart}：优先用链接里的数字 id，
 * 只有 {@code bilibili://comment/detail/17/...} 这一种（business_id=17）subject_id 才等于动态 id。
 *
 * <p>评论定位同样参照它：{@code pathSegs[3]} 是主评论（root_rpid），
 * {@code anchor}（= comment_secondary_id）是被回复的<b>子评论</b>，两个都要留着。
 */
public final class MsgLinkParser {

    public static final int TYPE_NONE = 0;
    public static final int TYPE_VIDEO_BVID = 1;
    public static final int TYPE_VIDEO_AID = 2;
    public static final int TYPE_DYNAMIC = 3;
    public static final int TYPE_ARTICLE = 4;

    /** 示例：https://www.bilibili.com/video/BV1MkTP61Efe */
    private static final Pattern P_BVID = Pattern.compile("/video/(BV[0-9A-Za-z]+)");
    /** 示例：bilibili://video/116825157277312?page=0 */
    private static final Pattern P_VIDEO_AID = Pattern.compile("bilibili://video/(\\d+)");
    /**
     * 三种写法都要覆盖：
     * /opus/1264274871169318914#reply318563863153
     * bilibili://opus/detail/1264274871169318914?comment_root_id=...
     * bilibili://opus/1264274871169318914
     */
    private static final Pattern P_OPUS = Pattern.compile("(?:/opus/|bilibili://opus/)(?:detail/)?(\\d+)");
    /** 示例：https://t.bilibili.com/1264274871169318914?comment_on=1 */
    private static final Pattern P_TBI = Pattern.compile("t\\.bilibili\\.com/(\\d+)");
    /** 示例：bilibili://following/detail/1264274871169318914?comment_root_id=... */
    private static final Pattern P_FOLLOWING = Pattern.compile("bilibili://following/detail/(\\d+)");
    /** 示例：https://www.bilibili.com/read/cv123456 */
    private static final Pattern P_ARTICLE = Pattern.compile("/read/cv(\\d+)");
    /** 示例：bilibili://comment/detail/11/413634671/318507010561/?subType=0 */
    private static final Pattern P_COMMENT = Pattern.compile("bilibili://comment/(?:detail|msg_fold)/(\\d+)/(\\d+)/");
    /** 主评论：bilibili://comment/{detail,msg_fold}/<business_id>/<subject_id>/<root_rpid>/ */
    private static final Pattern P_COMMENT_ROOT =
            Pattern.compile("bilibili://comment/(?:detail|msg_fold)/\\d+/\\d+/(\\d+)/");
    /** 折叠消息把目标藏在 enterUri 里 */
    private static final Pattern P_ENTER = Pattern.compile("[?&]enterUri=([^&]+)");
    /** 被回复的子评论（comment_secondary_id） */
    private static final Pattern P_ANCHOR = Pattern.compile("[?&]anchor=(\\d+)");
    private static final Pattern P_SECOND = Pattern.compile("[?&]comment_secondary_id=(\\d+)");
    /** 主评论（opus/detail 链接里带的主评论 rpid） */
    private static final Pattern P_ROOT_ID = Pattern.compile("[?&]comment_root_id=(\\d+)");

    private MsgLinkParser() {
    }

    /** 解析结果：type 决定调哪个跳转方法 */
    public static final class Target {
        public final int type;
        public final long id;
        public final String bvid;
        /**
         * 楼中楼里的主评论 rpid（native_uri 的 pathSegs[3] / comment_root_id），没有则 0
         */
        public final long rootRpid;
        /**
         * 被回复的子评论 rpid（native_uri 的 anchor / comment_secondary_id），没有则 0。
         *
         * <p>这个值必须一路传到评论组件并当作 {@code seek_rpid}：实测 {@code reply/main}
         * 只有收到<b>子评论</b> rpid 时，才会把它放到返回主评论的预览第一位；
         * 传主评论 rpid 或 source_id 时，预览里根本没有这条子评论——表现就是
         * "跳到主评论了，但看不到被回复的那条子评论"。
         */
        public final long childRpid;

        private Target(int type, long id, String bvid, long rootRpid, long childRpid) {
            this.type = type;
            this.id = id;
            this.bvid = bvid;
            this.rootRpid = rootRpid;
            this.childRpid = childRpid;
        }

        public boolean isNone() {
            return type == TYPE_NONE;
        }

        Target withCommentIds(long rootRpid, long childRpid) {
            return new Target(type, id, bvid, rootRpid, childRpid);
        }
    }

    private static final Target NONE = new Target(TYPE_NONE, 0, null, 0, 0);

    /**
     * @param uri        B 站消息的 uri 字段（网页链接）
     * @param nativeUri  B 站消息的 native_uri 字段（App 深链接，可空）
     * @param businessId 消息的 business_id
     * @param subjectId  消息的 subject_id（只有 business_id=17 时才等于动态 id）
     */
    public static Target parse(String uri, String nativeUri, int businessId, long subjectId) {
        // 1) uri 里通常直接带 /opus/<dynId>、/video/BVxxx、/read/cvxxx，最可靠
        Target target = fromText(uri);
        if (target.isNone()) target = fromText(nativeUri);

        // 2) 折叠消息：目标藏在 enterUri 参数里（可能被 URL 编码）
        if (target.isNone()) {
            String enter = extractEnter(nativeUri);
            if (enter == null) enter = extractEnter(uri);
            if (enter != null) target = fromText(enter);
        }

        // 3) bilibili://comment/...：只有 business_id == 17 时 subject_id 才是动态 id；
        //    11/12 的 subject_id 是评论区 oid（实测 413634671 这类），绝不能当动态 id 用
        if (target.isNone()) {
            Matcher m = P_COMMENT.matcher(nativeUri == null ? "" : nativeUri);
            if (m.find()) {
                int bid = toInt(m.group(1));
                long sid = toLong(m.group(2));
                if (bid == 17 && sid > 0) target = new Target(TYPE_DYNAMIC, sid, null, 0, 0);
                else if (bid == 12 && sid > 0) target = new Target(TYPE_ARTICLE, sid, null, 0, 0);
            }
        }

        // 4) 最后才认 subject_id，且只认 business_id == 17
        if (target.isNone() && businessId == 17 && subjectId > 0) {
            target = new Target(TYPE_DYNAMIC, subjectId, null, 0, 0);
        }

        if (target.isNone()) return NONE;

        // 补评论定位用的两个 rpid：优先 native_uri（App 深链接才带 anchor / pathSegs[3]）
        long root = 0;
        long child = 0;
        for (String text : new String[]{nativeUri, uri}) {
            if (TextUtils.isEmpty(text)) continue;
            if (root == 0) root = firstGroup(P_COMMENT_ROOT, text);
            if (root == 0) root = firstGroup(P_ROOT_ID, text);
            if (child == 0) child = firstGroup(P_ANCHOR, text);
            if (child == 0) child = firstGroup(P_SECOND, text);
        }
        return target.withCommentIds(root, child);
    }

    private static Target fromText(String text) {
        if (TextUtils.isEmpty(text)) return NONE;

        Matcher m = P_BVID.matcher(text);
        if (m.find()) return new Target(TYPE_VIDEO_BVID, 0, m.group(1), 0, 0);

        m = P_OPUS.matcher(text);
        if (m.find()) return new Target(TYPE_DYNAMIC, toLong(m.group(1)), null, 0, 0);

        m = P_TBI.matcher(text);
        if (m.find()) return new Target(TYPE_DYNAMIC, toLong(m.group(1)), null, 0, 0);

        m = P_FOLLOWING.matcher(text);
        if (m.find()) return new Target(TYPE_DYNAMIC, toLong(m.group(1)), null, 0, 0);

        m = P_ARTICLE.matcher(text);
        if (m.find()) return new Target(TYPE_ARTICLE, toLong(m.group(1)), null, 0, 0);

        m = P_VIDEO_AID.matcher(text);
        if (m.find()) return new Target(TYPE_VIDEO_AID, toLong(m.group(1)), null, 0, 0);

        return NONE;
    }

    private static long firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? toLong(m.group(1)) : 0;
    }

    private static String extractEnter(String text) {
        if (TextUtils.isEmpty(text)) return null;
        Matcher m = P_ENTER.matcher(text);
        if (!m.find()) return null;
        String raw = m.group(1);
        try {
            //enterUri 可能被整段 URL 编码（%3A%2F%2F…）
            return URLDecoder.decode(raw, "UTF-8");
        } catch (Exception e) {
            return raw;
        }
    }

    private static long toLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }
}
