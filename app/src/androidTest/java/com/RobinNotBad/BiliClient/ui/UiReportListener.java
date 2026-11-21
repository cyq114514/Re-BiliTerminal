package com.RobinNotBad.BiliClient.ui;

import android.content.Context;
import android.util.Xml;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 云端 UI 冒烟的 JUnit XML 报告监听器。
 *
 * <p>为什么不用 Gradle connectedAndroidTest：云端 runner 已经拿到 build job 的 APK，直接
 * adb install + am instrument 可以避免重复编译；AndroidJUnitRunner 的 {@code -e listener}
 * 允许在设备侧把结果写成标准 JUnit XML，host 脚本再拉取并生成 HTML。
 *
 * <p>输出路径：目标应用外部目录 {@code ui-report/TEST-ui-smoke.xml}，由 adb pull 收集。
 * 不写任何业务数据，不访问网络。
 */
public class UiReportListener extends RunListener {

    private static final class TestCase {
        final String className;
        final String name;
        long startMs;
        long timeMs;
        String failureMessage;
        String failureTrace;
        boolean skipped;
        boolean assumptionFailure;

        TestCase(String className, String name) {
            this.className = className;
            this.name = name;
        }
    }

    private final Map<String, TestCase> cases = new LinkedHashMap<>();
    private final List<TestCase> order = new ArrayList<>();
    private long runStartMs;
    private Context targetContext;

    private TestCase getCase(Description description) {
        String key = description.getClassName() + "#" + description.getMethodName();
        TestCase item = cases.get(key);
        if (item == null) {
            item = new TestCase(description.getClassName(), description.getMethodName());
            cases.put(key, item);
            order.add(item);
        }
        return item;
    }

    @Override
    public void testRunStarted(Description description) {
        runStartMs = System.currentTimeMillis();
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Override
    public void testStarted(Description description) {
        TestCase item = getCase(description);
        item.startMs = System.currentTimeMillis();
    }

    @Override
    public void testFinished(Description description) {
        TestCase item = getCase(description);
        if (item.startMs > 0) item.timeMs = System.currentTimeMillis() - item.startMs;
    }

    @Override
    public void testFailure(Failure failure) {
        TestCase item = getCase(failure.getDescription());
        item.failureMessage = failure.getMessage();
        item.failureTrace = failure.getTrace();
        item.timeMs = Math.max(item.timeMs, System.currentTimeMillis() - item.startMs);
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
        TestCase item = getCase(failure.getDescription());
        // assumeFalse 是显式跳过，不是失败；不能把 message 写进 failure，
        // 否则会被 JUnit/CI 汇总当成失败（Wear 的横屏用例就是这样误报的）。
        item.skipped = true;
        item.assumptionFailure = true;
    }

    @Override
    public void testIgnored(Description description) {
        TestCase item = getCase(description);
        item.skipped = true;
    }

    @Override
    public void testRunFinished(Result result) {
        File dir = new File(targetContext.getExternalFilesDir(null), "ui-report");
        if (!dir.exists()) dir.mkdirs();
        File xmlFile = new File(dir, "TEST-ui-smoke.xml");
        File summaryFile = new File(dir, "summary.txt");
        int failures = 0;
        int skipped = 0;
        long suiteTime = Math.max(0, System.currentTimeMillis() - runStartMs);
        for (TestCase item : order) {
            if (item.failureMessage != null || item.failureTrace != null) failures++;
            if (item.skipped) skipped++;
        }
        try (FileOutputStream out = new FileOutputStream(xmlFile)) {
            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(out, "UTF-8");
            serializer.startDocument("UTF-8", true);
            serializer.startTag(null, "testsuites");
            serializer.startTag(null, "testsuite");
            serializer.attribute(null, "name", "ui-smoke");
            serializer.attribute(null, "tests", String.valueOf(order.size()));
            serializer.attribute(null, "failures", String.valueOf(failures));
            serializer.attribute(null, "errors", "0");
            serializer.attribute(null, "skipped", String.valueOf(skipped));
            serializer.attribute(null, "time", seconds(suiteTime));
            serializer.startTag(null, "properties");
            property(serializer, "device.package", targetContext.getPackageName());
            property(serializer, "device.sdk", String.valueOf(android.os.Build.VERSION.SDK_INT));
            property(serializer, "device.model", android.os.Build.MODEL);
            property(serializer, "device.release", android.os.Build.VERSION.RELEASE);
            serializer.endTag(null, "properties");
            for (TestCase item : order) {
                serializer.startTag(null, "testcase");
                serializer.attribute(null, "classname", item.className);
                serializer.attribute(null, "name", item.name);
                serializer.attribute(null, "time", seconds(item.timeMs));
                if (item.skipped) {
                    serializer.startTag(null, "skipped");
                    serializer.endTag(null, "skipped");
                }
                if (item.failureMessage != null || item.failureTrace != null) {
                    serializer.startTag(null, "failure");
                    serializer.attribute(null, "message", truncate(item.failureMessage));
                    serializer.text(item.failureTrace == null ? "" : item.failureTrace);
                    serializer.endTag(null, "failure");
                }
                serializer.endTag(null, "testcase");
            }
            serializer.endTag(null, "testsuite");
            serializer.endTag(null, "testsuites");
            serializer.endDocument();
            serializer.flush();
            StringBuilder summary = new StringBuilder();
            summary.append("tests=").append(order.size())
                    .append(" failures=").append(failures)
                    .append(" skipped=").append(skipped)
                    .append(" time=").append(seconds(suiteTime))
                    .append('\n');
            for (TestCase item : order) {
                summary.append(item.failureMessage == null ? "PASS" : (item.skipped ? "SKIP" : "FAIL"))
                        .append(' ').append(item.className).append('#').append(item.name).append('\n');
            }
            try (FileOutputStream summaryOut = new FileOutputStream(summaryFile)) {
                summaryOut.write(summary.toString().getBytes("UTF-8"));
            }
            System.out.println("UI_REPORT_XML=" + xmlFile.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("UI_REPORT_WRITE_FAILED=" + e);
        }
    }

    private static void property(XmlSerializer serializer, String name, String value) throws Exception {
        serializer.startTag(null, "property");
        serializer.attribute(null, "name", name);
        serializer.attribute(null, "value", value);
        serializer.endTag(null, "property");
    }

    private static String seconds(long ms) {
        return String.format(java.util.Locale.US, "%.3f", ms / 1000.0);
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }
}