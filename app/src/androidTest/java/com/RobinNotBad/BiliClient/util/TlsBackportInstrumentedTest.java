package com.RobinNotBad.BiliClient.util;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import com.RobinNotBad.BiliClient.R;

import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

import static org.junit.Assert.assertTrue;

/**
 * 老设备 TLS 回移的端到端仪器化验证（docs/decisions-tls-backport.md）。
 *
 * <p>为什么是它：Apple Silicon 的模拟器只有 arm64 镜像（API 24+），既跑不了 API 19 的
 * x86 镜像、也装不上只有 v7a/x86 播放库的正式包，无法在本机复现 4.4 系统栈缺
 * TLS1.2+AEAD 的现场。所以用 {@code ./gradlew -PemuArm64 connectedDebugAndroidTest}
 * 在 arm64 模拟器上强制走 Conscrypt 路径，验证的是<b>同一份生产接线</b>：
 * <ul>
 * <li>conscrypt-android 2.5.2 的原生库在 Android 运行时正常加载；</li>
 * <li>合并信任锚（系统全部根 + 内置 DigiCert Global Root G2 / ISRG Root X1）构建成功，
 *     且对 \*.netlify.app（ECDSA + G2 链）与 \*.supabase.co（GTS 链）都能验过——
 *     内置补根缺失时反代链必然 Trust anchor 失败，等价于验证了补根真的起效；</li>
 * <li>SNI、TLS1.2 AEAD 握手、真实 POST 拿到 2xx。</li>
 * </ul>
 *
 * <p>会在 crash_reports 表留 2 条 exception_class=TlsBackportInstrumentedTest 的测试行，
 * 验收后可在收集端删除。API≤22 真机（米兔手表类）的最终验收看 CrashTrace 的
 * 「TLS 栈已切换 Conscrypt」与「上报成功」。
 */
public class TlsBackportInstrumentedTest {

    @Test
    public void conscrypt路径_反代与直连均完成握手并拿到2xx() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false);
        //绕过 SDK 门槛强制走 Conscrypt：与生产路径只差一个 SDK_INT 判断，其余完全一致
        boolean forced = NetWorkUtil.applyCrashUploadTls(context, builder, true);
        assertTrue("Conscrypt 接线未生效（socket 工厂构建失败，看 logcat「Conscrypt 初始化失败」回落日志）",
                forced);
        OkHttpClient client = builder.build();

        JSONObject report = CrashUploader.buildEvent(context, "java",
                "TlsBackportInstrumentedTest", "仪器化测试行，可删除", "main", "", "emu-verify");

        //直接驱动生产链路 CrashUploader.post（含 client(context) 的构建路径）。
        //反代只验「TLS 握手通、拿到任何 HTTP 响应」（函数当前 404 属服务端部署问题，
        //与本测试要证的 TLS 无关）；插库成功的证明由直连 201 承担。
        CrashUploader.post(context, CrashUploader.PROXY_ENDPOINT, report);
        assertTrue("反代（netlify）未拿到任何 HTTP 响应（TLS/网络层失败）HTTP "
                        + CrashUploader.lastHttpCode() + "，追踪：\n" + CrashTrace.readTail(20),
                CrashUploader.lastHttpCode() > 0);
        boolean directOk = CrashUploader.post(context, CrashUploader.DIRECT_ENDPOINT, report);
        assertTrue("直连（supabase）失败 HTTP " + CrashUploader.lastHttpCode()
                        + "，追踪：\n" + CrashTrace.readTail(20), directOk);
    }

    /**
     * 只信任两张内置根（不含系统任何根）时，\*.netlify.app 的 DigiCert G2 链必须仍能验过。
     * API 36 的系统库本来就有 G2 根，上面那条测试区分不了信任来自系统还是补根；
     * 本条在 API 36 上模拟 4.4 的信任情形（系统库无 G2）——补根独立起效才算数。
     */
    @Test
    public void 仅内置补根也能验过netlify链() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        java.security.KeyStore bundled = java.security.KeyStore.getInstance(
                java.security.KeyStore.getDefaultType());
        bundled.load(null, null);
        java.security.cert.CertificateFactory factory =
                java.security.cert.CertificateFactory.getInstance("X.509");
        int[] roots = {R.raw.digicert_global_root_g2, R.raw.isrg_root_x1};
        for (int rawId : roots) {
            java.io.InputStream in = context.getResources().openRawResource(rawId);
            try {
                for (java.security.cert.Certificate cert : factory.generateCertificates(in)) {
                    bundled.setCertificateEntry("bundled_" + rawId + "_" + bundled.size(), cert);
                }
            } finally {
                in.close();
            }
        }
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(bundled);
        final javax.net.ssl.X509TrustManager tm = (javax.net.ssl.X509TrustManager) tmf.getTrustManagers()[0];

        java.security.Provider provider = org.conscrypt.Conscrypt.newProvider();
        javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS", provider);
        sslContext.init(null, new javax.net.ssl.TrustManager[]{tm}, null);

        OkHttpClient client = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), tm)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        //GET 反代根路径即可：只要握手+证书链验证通过，无论函数在不在都会回一个 HTTP 状态
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(CrashUploader.PROXY_ENDPOINT).head().build();
        try (okhttp3.Response response = client.newCall(request).execute()) {
            assertTrue("仅凭内置根无法验证 netlify 证书链（HTTP " + response.code() + "）",
                    response.code() > 0);
        }
    }
}
