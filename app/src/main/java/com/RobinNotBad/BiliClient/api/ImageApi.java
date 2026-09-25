package com.RobinNotBad.BiliClient.api;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import androidx.exifinterface.media.ExifInterface;

import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * B站图床上传（web端 upload_bfs 接口，动态图片与评论图片共用）
 * 动态业务 biz=new_dyn，评论业务 biz=new_reply
 */
public class ImageApi {

    public static final String BIZ_DYNAMIC = "new_dyn";
    public static final String BIZ_REPLY = "new_reply";

    /**单张本地图片读取后的产物，用于上传*/
    public static class PreparedImage {
        public final byte[] data;
        public final String fileName;
        public final String mimeType;

        public PreparedImage(byte[] data, String fileName, String mimeType) {
            this.data = data;
            this.fileName = fileName;
            this.mimeType = mimeType;
        }
    }

    /**上传成功后的图片信息，转成动态/评论发布接口需要的JSON*/
    public static class UploadedImage {
        public String url;
        public int width;
        public int height;
        public double sizeKB;

        /**发布动态时 dyn_req.pics 数组元素的格式*/
        public JSONObject toDynamicPicJson() throws JSONException {
            return new JSONObject()
                    .put("img_src", url)
                    .put("img_width", width)
                    .put("img_height", height)
                    .put("img_size", sizeKB);
        }

        /**发布图片评论时 content.pictures 数组元素的格式*/
        public JSONObject toReplyPicJson() throws JSONException {
            return new JSONObject()
                    .put("img_url", url)
                    .put("img_width", width)
                    .put("img_height", height)
                    .put("img_size", sizeKB);
        }
    }

    /**
     * 读取本地图片并整理成可上传的格式：
     * gif 原样透传（保住动图），png 不大时原样透传（保住透明通道），
     * 其余解码后按最长边2048缩放、按EXIF方向转正，重压缩为jpg。
     */
    public static PreparedImage prepareImage(Context context, Uri uri) throws IOException {
        byte[] raw = readAllBytes(context, uri);
        if (raw == null || raw.length == 0) throw new IOException("无法读取图片");

        String mime = context.getContentResolver().getType(uri);
        if (mime == null || !mime.startsWith("image/")) mime = sniffMimeType(raw);
        String lower = mime.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        BitmapFactory.Options boundsOpt = new BitmapFactory.Options();
        boundsOpt.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, boundsOpt);
        if (boundsOpt.outWidth <= 0 || boundsOpt.outHeight <= 0) throw new IOException("无法解码图片");

        if (lower.contains("gif")) {
            if (raw.length > 20 * 1024 * 1024) throw new IOException("GIF过大（超过20MB）");
            return new PreparedImage(raw, "img_" + now + ".gif", "image/gif");
        }

        if (lower.contains("png") && raw.length <= 8 * 1024 * 1024) {
            return new PreparedImage(raw, "img_" + now + ".png", "image/png");
        }

        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = calcInSampleSize(boundsOpt, 2048);
        Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length, opt);
        if (bitmap == null) throw new IOException("无法解码图片");
        bitmap = rotateByExif(context, uri, bitmap);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
        bitmap.recycle();
        return new PreparedImage(baos.toByteArray(), "img_" + now + ".jpg", "image/jpeg");
    }

    /**上传图片到B站图床，返回图片外链与尺寸信息*/
    public static UploadedImage uploadImage(byte[] imageData, String fileName, String mimeType, String biz) throws IOException, JSONException {
        String csrf = SharedPreferencesUtil.getString("csrf", "");
        String url = "https://api.bilibili.com/x/dynamic/feed/draw/upload_bfs";

        RequestBody fileBody = RequestBody.create(MediaType.parse(mimeType), imageData);
        MultipartBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file_up", fileName, fileBody)
                .addFormDataPart("biz", biz)
                .addFormDataPart("category", "daily")
                .addFormDataPart("csrf", csrf)
                .build();

        Request.Builder requestBuilder = new Request.Builder().url(url).post(multipartBody);
        for (int i = 0; i < NetWorkUtil.webHeaders.size(); i += 2)
            requestBuilder.addHeader(NetWorkUtil.webHeaders.get(i), NetWorkUtil.webHeaders.get(i + 1));
        //okhttp会依据MultipartBody自动覆盖Content-Type，这里手动塞进去的通用头里没有该项，无需处理

        Response resp = NetWorkUtil.getOkHttpInstance().newCall(requestBuilder.build()).execute();
        ResponseBody body = resp.body();
        if (body == null) throw new IOException("上传响应为空");
        String json = body.string();
        Logu.v("upload_bfs resp=" + json);

        JSONObject result = new JSONObject(json);
        int code = result.optInt("code", -1);
        if (code != 0) throw new IOException("图片上传失败：" + result.optString("message", String.valueOf(code)));
        JSONObject data = result.optJSONObject("data");
        if (data == null) throw new IOException("图片上传失败：data为空");

        UploadedImage image = new UploadedImage();
        image.url = data.optString("image_url", "");
        if (image.url.startsWith("http://")) image.url = "https://" + image.url.substring(7);
        image.width = data.optInt("image_width", 0);
        image.height = data.optInt("image_height", 0);
        image.sizeKB = data.optDouble("img_size", Math.round(imageData.length / 1024.0 * 10) / 10.0);
        if (image.url.isEmpty()) throw new IOException("图片上传失败：未返回图片链接");
        return image;
    }

    private static byte[] readAllBytes(Context context, Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            return NetWorkUtil.readStream(in);
        }
    }

    private static String sniffMimeType(byte[] raw) {
        if (raw.length >= 12 && raw[0] == 'R' && raw[1] == 'I' && raw[2] == 'F' && raw[3] == 'F'
                && raw[8] == 'W' && raw[9] == 'E' && raw[10] == 'B' && raw[11] == 'P') return "image/webp";
        if (raw.length >= 8 && raw[0] == (byte) 0x89 && raw[1] == 'P' && raw[2] == 'N' && raw[3] == 'G') return "image/png";
        if (raw.length >= 3 && raw[0] == (byte) 0xFF && raw[1] == (byte) 0xD8 && raw[2] == (byte) 0xFF) return "image/jpeg";
        if (raw.length >= 2 && raw[0] == 'B' && raw[1] == 'M') return "image/bmp";
        return "image/jpeg";
    }

    private static int calcInSampleSize(BitmapFactory.Options options, int maxSide) {
        int side = Math.max(options.outWidth, options.outHeight);
        int inSampleSize = 1;
        while (side / (inSampleSize * 2) >= maxSide) inSampleSize *= 2;
        return inSampleSize;
    }

    private static Bitmap rotateByExif(Context context, Uri uri, Bitmap bitmap) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return bitmap;
            int orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int degrees = 0;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: degrees = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: degrees = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: degrees = 270; break;
            }
            if (degrees == 0) return bitmap;
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle();
            return rotated;
        } catch (Exception e) {
            return bitmap;
        }
    }
}
