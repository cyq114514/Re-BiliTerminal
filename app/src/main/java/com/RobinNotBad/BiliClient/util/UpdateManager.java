package com.RobinNotBad.BiliClient.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.activity.settings.UpdateActivity;
import com.RobinNotBad.BiliClient.model.UpdateInfo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 应用内自动检查更新（数据源：本仓库 cyq114514/Re-BiliTerminal 的 GitHub Releases）。
 *
 * 双通道设计：
 * - 主通道：release 资产 update.json（CI 发布时自动生成上传），走
 *   releases/latest/download/update.json 稳定直链。该地址在 github.com 域下，
 *   可被 ghproxy 系镜像整体前缀代理，且不占 api.github.com 的 60 次/小时限流。
 * - 兜底通道：GitHub Releases API（releases/latest），从 release 说明里的
 *   <!--ReBiliTerminal-update versionCode=... sha256=...--> 元数据注释解析版本号。
 *   老版本 release 两者皆无时 versionCode 为 0，自动检查静默跳过，手动检查给出说明。
 *
 * 镜像：为国内网络环境提供可选镜像前缀（只重写 github.com 开头的 URL，applyMirror），
 * 默认直连。镜像列表放任用户选择而非硬编码，公共镜像可用性经常变化。
 *
 * 独立 OkHttpClient 的原因：更新链路必须 followRedirects(true)（GitHub 资产会 302 到
 * objects.githubusercontent.com，全局客户端的重定向白名单只放行 B 站域），且绝不注入
 * B 站 Cookie、不挂 CookieSaveInterceptor——凭证最小化，更新链路对登录态零依赖。
 * 但 TLS 兼容（API≤22 默认不启用 TLS1.2，访问 GitHub 必需）与 IPv4 DNS 仍复用全局配置。
 *
 * 下载到应用私有 cache 目录（免存储权限），完成后做 SHA-256 校验再拉起系统安装器；
 * 支持断点续传，续传时先把已下载分片喂给摘要，保证哈希覆盖完整文件。
 */
public class UpdateManager {

    public static final String REPO_OWNER_SLASH_NAME = "cyq114514/Re-BiliTerminal";
    /**update.json 资产的稳定直链：始终指向"最新 release"上的该资产*/
    public static final String UPDATE_JSON_URL = "https://github.com/" + REPO_OWNER_SLASH_NAME + "/releases/latest/download/update.json";
    private static final String RELEASE_API = "https://api.github.com/repos/" + REPO_OWNER_SLASH_NAME + "/releases/latest";
    private static final String RELEASE_LATEST_DOWNLOAD_DIR = "https://github.com/" + REPO_OWNER_SLASH_NAME + "/releases/latest/download/";
    private static final String USER_AGENT = "Re-BiliTerminal-UpdateCheck";
    private static final String APK_FILE_NAME = "Re-BiliTerminal-update.apk";

    /**内置镜像预设（前缀 + 直连）；公共镜像可用性经常变化，用户可在检查更新页切换或自定义*/
    public static final String[] MIRROR_PRESETS = {"", "https://ghfast.top/", "https://gh-proxy.com/"};
    private static final String KEY_MIRROR_PREFIX = "update_mirror_prefix";
    /**用户点过"忽略此版本"的版本名（每次发现新版本只会提示一次）*/
    private static final String KEY_SKIPPED_VERSION = "update_skipped_version";

    private static final Pattern VERSION_CODE_PATTERN = Pattern.compile("versionCode\\s*=\\s*(\\d+)");
    private static final Pattern SHA256_PATTERN = Pattern.compile("sha256\\s*=\\s*([0-9a-fA-F]{64})");

    private static volatile OkHttpClient updateClient;
    private static volatile boolean downloadCanceled = false;
    private static volatile boolean autoChecked = false;   //每次进程生命周期只静默检查一次
    private static volatile UpdateInfo cachedInfo = null;

    public interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    private static OkHttpClient client() {
        if (updateClient == null) {
            synchronized (UpdateManager.class) {
                if (updateClient == null) {
                    updateClient = NetWorkUtil.setOkHttpSsl(new OkHttpClient.Builder())
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .dns(new NetWorkUtil.Inet4Selector())
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(60, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return updateClient;
    }

    /**当前生效的镜像前缀（空串 = 直连）。*/
    public static String getMirrorPrefix() {
        return SharedPreferencesUtil.getString(KEY_MIRROR_PREFIX, "");
    }

    public static void setMirrorPrefix(String prefix) {
        SharedPreferencesUtil.putString(KEY_MIRROR_PREFIX, prefix == null ? "" : prefix);
    }

    /**镜像重写：仅作用于 github.com 的下载/直链地址，前缀即完整的镜像基地址（含尾斜杠）。*/
    public static String applyMirror(String githubUrl) {
        String prefix = getMirrorPrefix();
        if (prefix != null && !prefix.isEmpty() && githubUrl.startsWith("https://github.com/"))
            return prefix + githubUrl;
        return githubUrl;
    }

    public static UpdateInfo getCachedInfo() {
        return cachedInfo;
    }

    /**当前已安装的 versionCode。*/
    public static long getInstalledVersionCode() {
        try {
            PackageInfo info = BiliTerminal.context.getPackageManager()
                    .getPackageInfo(BiliTerminal.context.getPackageName(), 0);
            return info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /**当前已安装的版本名（如 1.1.4）。*/
    public static String getInstalledVersionName() {
        try {
            PackageInfo info = BiliTerminal.context.getPackageManager()
                    .getPackageInfo(BiliTerminal.context.getPackageName(), 0);
            return info.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 拉取最新版本信息：update.json 资产优先，API 兜底。
     * 网络/解析失败抛 IOException；两路都拿不到版本元数据时返回 versionCode=0 的
     * {@link UpdateInfo}（isUsable()==false，只够展示"发布页/日志"，无法自动比较版本）。
     */
    public static UpdateInfo fetchLatest() throws IOException {
        UpdateInfo info;
        try {
            info = fetchViaUpdateJson();
            if (info != null) {
                cachedInfo = info;
                return info;
            }
        } catch (IOException ignored) {
            //主通道（可能带镜像）失败：落到 API 兜底，API 失败时整体失败
        }
        info = fetchViaApi();
        cachedInfo = info;
        return info;
    }

    /**主通道：release 资产 update.json（可被镜像加速）。资产不存在/不含文件名时返回 null 走兜底。*/
    private static UpdateInfo fetchViaUpdateJson() throws IOException {
        Request request = new Request.Builder().url(applyMirror(UPDATE_JSON_URL)).header("User-Agent", USER_AGENT).build();
        try (Response response = client().newCall(request).execute()) {
            try (ResponseBody body = response.body()) {
                if (response.code() == 404) return null;   //最新 release 还没上传 update.json：走 API 兜底
                if (!response.isSuccessful() || body == null)
                    throw new IOException("HTTP " + response.code());
                JSONObject json = new JSONObject(body.string());
                UpdateInfo info = new UpdateInfo();
                info.versionCode = json.optLong("versionCode", 0);
                info.versionName = json.optString("versionName", "");
                info.tagName = json.optString("tagName", info.versionName.isEmpty() ? "" : "v" + info.versionName);
                info.notes = json.optString("notes", "");
                info.releaseUrl = json.optString("htmlUrl", "https://github.com/" + REPO_OWNER_SLASH_NAME + "/releases/latest");
                info.sha256 = json.optString("sha256", null);
                String fileName = json.optString("fileName", "");
                //unsigned 包无法覆盖安装：视为没有可用更新资产，走 API 兜底（同样会过滤）
                if (info.versionName.isEmpty() || fileName.isEmpty() || fileName.toLowerCase(Locale.ROOT).contains("unsigned"))
                    return null;
                info.apkUrl = applyMirror(RELEASE_LATEST_DOWNLOAD_DIR + fileName);
                return info;
            } catch (org.json.JSONException e) {
                return null;   //内容异常当资产缺失处理，走 API 兜底
            }
        }
    }

    /**兜底通道：GitHub Releases API，版本元数据取自 release 说明里的注释。*/
    private static UpdateInfo fetchViaApi() throws IOException {
        Request request = new Request.Builder()
                .url(RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build();
        try (Response response = client().newCall(request).execute()) {
            try (ResponseBody body = response.body()) {
                if (!response.isSuccessful() || body == null)
                    throw new IOException("HTTP " + response.code() + (response.code() == 403 ? "（可能触发了接口频率限制）" : ""));
                UpdateInfo info = parseRelease(new JSONObject(body.string()));
                if (info.apkUrl != null && !info.apkUrl.isEmpty()) info.apkUrl = applyMirror(info.apkUrl);
                return info;
            } catch (org.json.JSONException e) {
                throw new IOException("更新信息解析失败", e);
            }
        }
    }

    private static UpdateInfo parseRelease(JSONObject release) throws org.json.JSONException {
        UpdateInfo info = new UpdateInfo();
        info.tagName = release.optString("tag_name", "");
        info.releaseUrl = release.optString("html_url", "");
        info.notes = release.optString("body", "");
        info.versionName = info.tagName.startsWith("v") ? info.tagName.substring(1) : info.tagName;

        //从资产里挑出可安装的 APK（过滤 unsigned 包：未签名无法覆盖安装）
        org.json.JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "").toLowerCase(Locale.ROOT);
            String url = asset.optString("browser_download_url", "");
            if (url.isEmpty()) continue;
            if (name.endsWith(".apk") && !name.contains("unsigned")) {
                if (info.apkUrl == null || info.apkUrl.isEmpty()) info.apkUrl = url;
            }
        }

        //机器可读元数据：release 说明里的 <!--ReBiliTerminal-update versionCode=... sha256=...-->
        long versionCode = 0;
        String sha256 = null;
        int metaStart = info.notes.indexOf("ReBiliTerminal-update");
        if (metaStart != -1) {
            int metaEnd = info.notes.indexOf("-->", metaStart);
            String meta = info.notes.substring(metaStart,
                    metaEnd == -1 ? Math.min(metaStart + 500, info.notes.length()) : metaEnd);
            Matcher codeMatcher = VERSION_CODE_PATTERN.matcher(meta);
            if (codeMatcher.find()) versionCode = parseLong(codeMatcher.group(1));
            Matcher shaMatcher = SHA256_PATTERN.matcher(meta);
            if (shaMatcher.find()) sha256 = shaMatcher.group(1);
        }
        info.versionCode = versionCode;
        info.sha256 = sha256;
        return info;
    }

    private static long parseLong(String str) {
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 该 release 是否是一个可提示的更新（元数据齐全、有 APK、版本比当前新）。
     * 判断依据：versionName 语义化比较（版本名是每次发版必然变化的字段，
     * 从根上规避 versionCode 重复的历史问题）；版本名任一侧解析失败时退回 versionCode 比较。
     */
    public static boolean isNewer(UpdateInfo info) {
        if (info == null || !info.isUsable()) return false;
        long latest = VersionNameUtil.parse(info.versionName);
        long installed = VersionNameUtil.parse(getInstalledVersionName());
        if (latest >= 0 && installed >= 0) return latest > installed;
        return info.versionCode > 0 && info.versionCode > getInstalledVersionCode();
    }

    /**
     * 启动时的静默自动检查：进程内只执行一次；网络失败完全静默；
     * 发现新版本且用户没有忽略过该版本时，在当前页面弹一次性提示。
     */
    public static void autoCheck(final BaseActivity activity) {
        if (autoChecked) return;
        autoChecked = true;
        CenterThreadPool.run(() -> {
            try {
                UpdateInfo info = fetchLatest();
                if (!isNewer(info)) {
                    deleteOldApkFile();   //已是最新：顺手清理残留的更新包
                    return;
                }
                String versionName = info.versionName;
                if (versionName.equals(SharedPreferencesUtil.getString(KEY_SKIPPED_VERSION, ""))) return;

                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed() || activity.isFinishing()) return;
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                            .setTitle("发现新版本 " + info.versionName)
                            .setMessage(buildShortNotes(info))
                            .setPositiveButton("立即更新", (dialog, which) ->
                                    activity.startActivity(new Intent(activity, UpdateActivity.class)))
                            .setNeutralButton("忽略此版本", (dialog, which) ->
                                    SharedPreferencesUtil.putString(KEY_SKIPPED_VERSION, versionName))
                            .setNegativeButton("以后再说", null)
                            .show();
                });
            } catch (Exception ignored) {
                //自动检查不打扰用户：网络不通/接口异常就当没检查过
            }
        });
    }

    private static String buildShortNotes(UpdateInfo info) {
        String notes = info.notes == null ? "" : info.notes
                .replace("<!--ReBiliTerminal-update", "").replaceAll("-->\\s*$", "").trim();
        if (notes.length() > 500) notes = notes.substring(0, 500) + "……";
        return "最新版本：" + info.versionName + "\n当前版本：" + getInstalledVersionName() + "\n\n" + notes;
    }

    /**下载目标目录（应用私有 cache，免存储权限）。*/
    private static File updateDir(Context context) {
        File base = context.getExternalCacheDir() != null ? context.getExternalCacheDir() : context.getCacheDir();
        File dir = new File(base, "update");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getApkFile(Context context) {
        return new File(updateDir(context), APK_FILE_NAME);
    }

    /**清理残留的更新包（下载完成安装后/确认无需更新时调用）。*/
    public static void deleteOldApkFile() {
        try {
            File file = getApkFile(BiliTerminal.context);
            if (file.exists()) file.delete();
        } catch (Exception ignored) {
        }
    }

    public static void cancelDownload() {
        downloadCanceled = true;
    }

    /**
     * 下载更新包（断点续传 + 流式 SHA-256 校验）。
     * 校验失败/取消时抛 IOException 并清理半成品，调用方在后台线程调用。
     */
    public static File downloadApk(Context context, UpdateInfo info, ProgressListener listener) throws IOException {
        downloadCanceled = false;
        File file = getApkFile(context);
        File dir = updateDir(context);

        long existing = file.exists() ? file.length() : 0;
        Request.Builder requestBuilder = new Request.Builder().url(info.apkUrl).header("User-Agent", USER_AGENT);
        if (existing > 0) requestBuilder.header("Range", "bytes=" + existing + "-");

        Response response = client().newCall(requestBuilder.build()).execute();
        try {
            if (existing > 0 && response.code() == 200) {
                //服务器忽略 Range 返回了全量 200：必须丢弃残片从头写，否则产出永久损坏的 APK
                response.close();
                file.delete();
                existing = 0;
                response = client().newCall(new Request.Builder().url(info.apkUrl).header("User-Agent", USER_AGENT).build()).execute();
                if (!response.isSuccessful())
                    throw new IOException("下载失败：HTTP " + response.code());
            } else if (!response.isSuccessful() && response.code() != 206) {
                throw new IOException("下载失败：HTTP " + response.code());
            }

            ResponseBody body = response.body();
            if (body == null) throw new IOException("下载失败：响应体为空");
            boolean append = existing > 0 && response.code() == 206;
            long total = append ? existing + Math.max(0, body.contentLength()) : Math.max(0, body.contentLength());

            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IOException("设备不支持 SHA-256", e);
            }

            InputStream input = body.byteStream();
            OutputStream output = new FileOutputStream(file, append);
            long read = existing;   //声明在内层 try 之外：下载结束后还要上报最终进度
            try {
                if (append) {
                    //续传：先把磁盘上已有的分片喂给摘要，最终哈希才能覆盖完整文件
                    try (InputStream fin = new FileInputStream(file)) {
                        byte[] head = new byte[8192];
                        int hn;
                        while ((hn = fin.read(head)) != -1) digest.update(head, 0, hn);
                    }
                }
                byte[] buffer = new byte[8192];
                int count;
                long lastReport = 0;
                while ((count = input.read(buffer)) != -1) {
                    if (downloadCanceled) throw new IOException("下载已取消");
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    read += count;
                    long now = System.currentTimeMillis();
                    if (now - lastReport >= 200) {
                        lastReport = now;
                        listener.onProgress(read, total);
                    }
                }
                output.flush();
            } finally {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
                input.close();
            }

            listener.onProgress(read, read);

            if (info.sha256 != null && info.sha256.length() == 64) {
                String actual = toHex(digest.digest());
                if (!actual.equalsIgnoreCase(info.sha256)) {
                    file.delete();
                    throw new IOException("更新包校验失败（SHA-256 不符），已删除下载内容，请重试");
                }
            }
            return file;
        } catch (IOException e) {
            downloadCanceled = false;
            throw e;
        } finally {
            response.close();
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    /**拉起系统安装器。Android 8+ 先检查"安装未知应用"授权，未授予时引导到设置页。*/
    public static void installApk(Activity activity, File apkFile) {
        if (Build.VERSION.SDK_INT >= 26) {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                MsgUtil.showMsg("请先允许本应用\"安装未知应用\"，再重新点击安装");
                try {
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName())));
                } catch (Exception e) {
                    //个别 ROM 没有该设置页：退回应用详情页
                    activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + activity.getPackageName())));
                }
                return;
            }
        }

        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".FileProvider", apkFile);
        } else {
            uri = Uri.fromFile(apkFile);
        }
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }
}
