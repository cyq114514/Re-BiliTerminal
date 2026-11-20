package com.RobinNotBad.BiliClient.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.RobinNotBad.BiliClient.model.DashData;
import com.RobinNotBad.BiliClient.model.LiveRoom;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * {@link GsonUtil} 的 JVM 用例：int/long 字符串兼容、列表解析、往返序列化、非法 JSON 兜底，
 * 以及 LiveRoom / DashData 两个手写反序列化器的本地样例。
 *
 * <p>DashData 的解析走 {@code org.json}，必须在 Robolectric 下运行（全局 robolectric.properties
 * 已固定 sdk=33 / conscryptMode=OFF）。样例全部是本地虚构 JSON，不访问网络、不含真实账号或 Cookie。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class GsonUtilTest {

    /** 覆盖 int/long 的原始类型与装箱类型，用于验证 IntOrString/LongOrString 适配器。 */
    public static class Bean {
        public int anInt;
        public Integer boxedInt;
        public long aLong;
        public Long boxedLong;
        public String text;
    }

    private static final String LIVE_ROOM_JSON = "{"
            + "\"roomid\":\"123456\","
            + "\"short_id\":7,"
            + "\"uid\":10086,"
            + "\"title\":\"离线测试直播间\","
            + "\"uname\":\"示例用户\","
            + "\"online\":\"77\","
            + "\"attention\":5,"
            + "\"area_id\":\"9\","
            + "\"area_name\":\"虚拟区\","
            + "\"area_parent_id\":1,"
            + "\"live_status\":1,"
            + "\"is_portrait\":true,"
            + "\"verify\":{\"role\":1,\"desc\":\"示例认证\",\"type\":0}"
            + "}";

    private static final String DASH_JSON = "{"
            + "\"duration\":120,"
            + "\"minBufferTime\":1.5,"
            + "\"video\":[{"
            + "  \"id\":80,"
            + "  \"baseUrl\":\"https://cdn.example.com/v80.m4s\","
            + "  \"backupUrl\":[\"https://cdn2.example.com/v80.m4s\"],"
            + "  \"bandwidth\":1000,"
            + "  \"width\":1920,"
            + "  \"height\":1080"
            + "}],"
            + "\"audio\":[{"
            + "  \"id\":30280,"
            + "  \"baseUrl\":\"https://cdn.example.com/a30280.m4s\","
            + "  \"bandwidth\":320000"
            + "}],"
            + "\"dolby\":{\"type\":1,\"audio\":{"
            + "  \"id\":30250,"
            + "  \"baseUrl\":\"https://cdn.example.com/dolby.m4s\","
            + "  \"bandwidth\":448000"
            + "}},"
            + "\"flac\":{\"display\":true,\"audio\":{"
            + "  \"id\":30251,"
            + "  \"baseUrl\":\"https://cdn.example.com/flac.m4s\","
            + "  \"bandwidth\":900000"
            + "}}"
            + "}";

    // ---------------------------------------------------------------- 空值/非法 JSON

    @Test
    public void fromJsonReturnsNullForNullOrEmptyInput() {
        assertNull(GsonUtil.fromJson(null, Bean.class));
        assertNull(GsonUtil.fromJson("", Bean.class));
        // JSON 字面量 null 反序列化结果也是 null
        assertNull(GsonUtil.fromJson("null", Bean.class));
    }

    @Test
    public void invalidJsonReturnsNullInsteadOfThrowing() {
        assertNull(GsonUtil.fromJson("{invalid", Bean.class));
        assertNull(GsonUtil.fromJson("not json", Bean.class));
        // 类型不匹配（数组 → 对象）同样兜底为 null
        assertNull(GsonUtil.fromJson("[1,2]", Bean.class));
    }

    // ---------------------------------------------------------------- int/long 兼容

    @Test
    public void intAndLongFieldsAcceptStringValues() {
        Bean bean = GsonUtil.fromJson("{\"anInt\":\"42\",\"boxedInt\":\"43\","
                + "\"aLong\":\"1234567890123\",\"boxedLong\":\"44\"}", Bean.class);
        assertNotNull(bean);
        assertEquals(42, bean.anInt);
        assertEquals(Integer.valueOf(43), bean.boxedInt);
        assertEquals(1234567890123L, bean.aLong);
        assertEquals(Long.valueOf(44), bean.boxedLong);
    }

    @Test
    public void intAndLongFieldsAcceptBooleanValues() {
        Bean bean = GsonUtil.fromJson("{\"anInt\":true,\"boxedInt\":false,"
                + "\"aLong\":true,\"boxedLong\":false}", Bean.class);
        assertNotNull(bean);
        assertEquals(1, bean.anInt);
        assertEquals(Integer.valueOf(0), bean.boxedInt);
        assertEquals(1L, bean.aLong);
        assertEquals(Long.valueOf(0L), bean.boxedLong);
    }

    @Test
    public void intAndLongFieldsTruncateFloatingPointValues() {
        Bean bean = GsonUtil.fromJson("{\"anInt\":12.9,\"boxedInt\":2.1,"
                + "\"aLong\":1.9,\"boxedLong\":3.7}", Bean.class);
        assertNotNull(bean);
        assertEquals(12, bean.anInt);
        assertEquals(Integer.valueOf(2), bean.boxedInt);
        assertEquals(1L, bean.aLong);
        assertEquals(Long.valueOf(3L), bean.boxedLong);
    }

    @Test
    public void invalidNumericStringsAndNullsFallBackToZero() {
        Bean bean = GsonUtil.fromJson("{\"anInt\":\"abc\",\"boxedInt\":\"\","
                + "\"aLong\":\"12x\",\"boxedLong\":\"not-a-number\"}", Bean.class);
        assertNotNull(bean);
        assertEquals(0, bean.anInt);
        assertEquals(Integer.valueOf(0), bean.boxedInt);
        assertEquals(0L, bean.aLong);
        assertEquals(Long.valueOf(0L), bean.boxedLong);

        Bean nulls = GsonUtil.fromJson("{\"anInt\":null,\"boxedInt\":null,"
                + "\"aLong\":null,\"boxedLong\":null}", Bean.class);
        assertNotNull(nulls);
        // 原始类型：JSON null 由 Gson 填默认值 0
        assertEquals(0, nulls.anInt);
        assertEquals(0L, nulls.aLong);
        // 装箱类型：Gson 的 nullSafe 包装对 JSON null 直接返回 null，不会调用自定义
        // deserializer（所以适配器里的 isJsonNull 分支对装箱字段不可达）。这是现有契约：
        // 业务代码读装箱字段前必须判空，不能假设它会变成 0。
        assertNull(nulls.boxedInt);
        assertNull(nulls.boxedLong);
    }

    // ---------------------------------------------------------------- 列表

    @Test
    public void fromJsonListParsesArray() {
        List<Bean> list = GsonUtil.fromJsonList(
                "[{\"anInt\":1},{\"anInt\":\"2\"},{\"anInt\":true}]", Bean.class);
        assertNotNull(list);
        assertEquals(3, list.size());
        assertEquals(1, list.get(0).anInt);
        assertEquals(2, list.get(1).anInt);
        assertEquals(1, list.get(2).anInt);

        List<Bean> empty = GsonUtil.fromJsonList("[]", Bean.class);
        assertNotNull(empty);
        assertTrue(empty.isEmpty());
    }

    @Test
    public void fromJsonListReturnsNullForNullOrInvalidInput() {
        assertNull(GsonUtil.fromJsonList(null, Bean.class));
        assertNull(GsonUtil.fromJsonList("", Bean.class));
        // 对象不是数组，解析失败应返回 null 而不是抛异常
        assertNull(GsonUtil.fromJsonList("{\"anInt\":1}", Bean.class));
    }

    // ---------------------------------------------------------------- 往返

    @Test
    public void toJsonRoundTripsAndSerializesNulls() {
        Bean bean = new Bean();
        bean.anInt = 7;
        bean.boxedInt = 8;
        bean.aLong = 9L;
        bean.boxedLong = 10L;
        bean.text = "hello";

        String json = GsonUtil.toJson(bean);
        assertTrue("JSON 应包含字段：" + json, json.contains("\"anInt\":7"));
        Bean back = GsonUtil.fromJson(json, Bean.class);
        assertNotNull(back);
        assertEquals(7, back.anInt);
        assertEquals(Integer.valueOf(8), back.boxedInt);
        assertEquals(9L, back.aLong);
        assertEquals(Long.valueOf(10L), back.boxedLong);
        assertEquals("hello", back.text);

        Bean withNullText = new Bean();
        withNullText.anInt = 1;
        // serializeNulls 已开启：null 字段也要出现在 JSON 里（下游按字段存在与否判断会依赖）
        assertTrue(GsonUtil.toJson(withNullText).contains("\"text\":null"));
    }

    // ---------------------------------------------------------------- LiveRoom

    @Test
    public void liveRoomSampleDeserializes() {
        LiveRoom room = GsonUtil.fromJson(LIVE_ROOM_JSON, LiveRoom.class);
        assertNotNull(room);
        assertEquals(123456L, room.roomid);
        assertEquals(7L, room.short_id);
        assertEquals(10086L, room.uid);
        assertEquals("离线测试直播间", room.title);
        assertEquals("示例用户", room.uname);
        assertEquals(77, room.online);
        assertEquals(5, room.attention);
        assertEquals(9, room.area_id);
        assertEquals("虚拟区", room.area_name);
        assertEquals(1, room.area_parent_id);
        assertEquals(1, room.live_status);
        assertTrue(room.is_portrait);
        assertNotNull(room.verify);
        assertEquals(1, room.verify.role);
        assertEquals("示例认证", room.verify.desc);
    }

    @Test
    public void liveRoomAcceptsAlternateRoomIdAndDropsPrimitiveVerify() {
        // @SerializedName alternate 兼容 room_id；verify 为原始类型时会被反序列化器移除
        LiveRoom room = GsonUtil.fromJson(
                "{\"room_id\":987654321,\"title\":\"另一个房间\",\"verify\":0}", LiveRoom.class);
        assertNotNull(room);
        assertEquals(987654321L, room.roomid);
        assertEquals("另一个房间", room.title);
        assertNull(room.verify);
    }

    // ---------------------------------------------------------------- DashData

    @Test
    public void dashDataSampleDeserializes() {
        DashData dash = GsonUtil.fromJson(DASH_JSON, DashData.class);
        assertNotNull(dash);
        assertEquals(120, dash.duration);
        assertEquals(1.5d, dash.minBufferTime, 1e-9);

        assertTrue(dash.hasVideo());
        assertEquals(1, dash.videoStreams.size());
        assertEquals(80, dash.videoStreams.get(0).id);
        assertEquals("https://cdn.example.com/v80.m4s", dash.videoStreams.get(0).baseUrl);
        assertEquals(1, dash.videoStreams.get(0).backupUrl.size());
        assertEquals(1920, dash.videoStreams.get(0).width);
        assertEquals(1080, dash.videoStreams.get(0).height);

        assertTrue(dash.hasAudio());
        assertEquals(1, dash.audioStreams.size());
        assertEquals(30280, dash.audioStreams.get(0).id);
        assertNotNull(dash.dolbyAudio);
        assertEquals(30250, dash.dolbyAudio.id);
        assertNotNull(dash.flacAudio);
        assertEquals(30251, dash.flacAudio.id);
        // 既有策略：无损优先于杜比，普通音频兜底
        assertEquals(30251, dash.getBestAudioStream().id);
    }

    @Test
    public void dashDataWithMinimalFieldsDoesNotCrash() {
        DashData dash = GsonUtil.fromJson("{\"duration\":5}", DashData.class);
        assertNotNull(dash);
        assertEquals(5, dash.duration);
        assertFalse(dash.hasVideo());
        assertFalse(dash.hasAudio());
        assertTrue(dash.videoStreams.isEmpty());
        assertTrue(dash.audioStreams.isEmpty());
        assertNull(dash.getBestAudioStream());
    }

    @Test
    public void dashDataFromNonObjectJsonReturnsEmptyInstance() {
        // 反序列化器对非 JSON 对象返回空 DashData（不抛异常）
        DashData dash = GsonUtil.fromJson("[]", DashData.class);
        assertNotNull(dash);
        assertFalse(dash.hasVideo());
        assertFalse(dash.hasAudio());
    }
}