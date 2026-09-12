package com.RobinNotBad.BiliClient.util;

import com.RobinNotBad.BiliClient.model.DanmakuElem;
import com.RobinNotBad.BiliClient.model.DmSegMobileReply;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Protobuf 解析器
 * 用于解析 Bilibili 新版弹幕接口返回的 protobuf 数据
 */
public class ProtobufParser {

    /**
     * 解析弹幕分段响应
     *
     * @param data protobuf 格式的字节数据
     * @return 弹幕分段响应对象
     */
    public static DmSegMobileReply parseDmSegMobileReply(byte[] data) throws IOException {
        DmSegMobileReply reply = new DmSegMobileReply();
        ByteArrayInputStream input = new ByteArrayInputStream(data);

        while (input.available() > 0) {
            int tag = readVarint(input);
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x07;

            if (fieldNumber == 1 && wireType == 2) { // elems 字段
                int length = readVarint(input);
                byte[] elemData = new byte[length];
                input.read(elemData);
                DanmakuElem elem = parseDanmakuElem(elemData);
                reply.elems.add(elem);
            } else {
                skipField(input, wireType);
            }
        }

        return reply;
    }

    /**
     * 解析弹幕元素
     *
     * @param data protobuf 格式的字节数据
     * @return 弹幕元素对象
     */
    private static DanmakuElem parseDanmakuElem(byte[] data) throws IOException {
        DanmakuElem elem = new DanmakuElem();
        ByteArrayInputStream input = new ByteArrayInputStream(data);

        while (input.available() > 0) {
            int tag = readVarint(input);
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x07;

            switch (fieldNumber) {
                case 1: // id (int64)
                    if (wireType == 0)
                        elem.id = readVarint64(input);
                    break;
                case 2: // progress (int32)
                    if (wireType == 0)
                        elem.progress = readVarint(input);
                    break;
                case 3: // mode (int32)
                    if (wireType == 0)
                        elem.mode = readVarint(input);
                    break;
                case 4: // fontsize (int32)
                    if (wireType == 0)
                        elem.fontsize = readVarint(input);
                    break;
                case 5: // color (uint32)
                    if (wireType == 0)
                        elem.color = readVarint(input);
                    break;
                case 6: // midHash (string)
                    if (wireType == 2)
                        elem.midHash = readString(input);
                    break;
                case 7: // content (string)
                    if (wireType == 2)
                        elem.content = readString(input);
                    break;
                case 8: // ctime (int64)
                    if (wireType == 0)
                        elem.ctime = readVarint64(input);
                    break;
                case 9: // weight (int32)
                    if (wireType == 0)
                        elem.weight = readVarint(input);
                    break;
                case 10: // action (string)
                    if (wireType == 2)
                        elem.action = readString(input);
                    break;
                case 11: // pool (int32)
                    if (wireType == 0)
                        elem.pool = readVarint(input);
                    break;
                case 12: // idStr (string)
                    if (wireType == 2)
                        elem.idStr = readString(input);
                    break;
                case 13: // attr (int32)
                    if (wireType == 0)
                        elem.attr = readVarint(input);
                    break;
                case 14: // animation (string)
                    if (wireType == 2)
                        elem.animation = readString(input);
                    break;
                default:
                    skipField(input, wireType);
                    break;
            }
        }

        return elem;
    }

    /**
     * 读取变长整数（varint）
     */
    private static int readVarint(ByteArrayInputStream input) throws IOException {
        int result = 0;
        int shift = 0;
        int b;

        do {
            if (shift >= 32) {
                throw new IOException("Varint too long");
            }
            b = input.read();
            if (b == -1) {
                throw new IOException("Unexpected end of stream");
            }
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);

        return result;
    }

    /**
     * 读取 64 位变长整数
     */
    private static long readVarint64(ByteArrayInputStream input) throws IOException {
        long result = 0;
        int shift = 0;
        int b;

        do {
            if (shift >= 64) {
                throw new IOException("Varint too long");
            }
            b = input.read();
            if (b == -1) {
                throw new IOException("Unexpected end of stream");
            }
            result |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);

        return result;
    }

    /**
     * 读取字符串
     */
    private static String readString(ByteArrayInputStream input) throws IOException {
        int length = readVarint(input);
        byte[] bytes = new byte[length];
        int bytesRead = input.read(bytes);
        if (bytesRead != length) {
            throw new IOException("Unexpected end of stream");
        }
        return new String(bytes, CompatUtil.getCharsetUTF8());
    }

    /**
     * 跳过字段
     */
    private static void skipField(ByteArrayInputStream input, int wireType) throws IOException {
        switch (wireType) {
            case 0: // Varint
                readVarint64(input);
                break;
            case 1: // 64-bit
                input.skip(8);
                break;
            case 2: // Length-delimited
                int length = readVarint(input);
                input.skip(length);
                break;
            case 5: // 32-bit
                input.skip(4);
                break;
            default:
                throw new IOException("Unknown wire type: " + wireType);
        }
    }
}
