package com.RobinNotBad.BiliClient.activity.settings;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.model.UpdateInfo;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.LinkUrlUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.UpdateManager;

import java.io.File;

/**
 * 检查更新页面：进入即自动检查（也承担设置页/更新弹窗的手动入口）。
 * 有更新时展示日志并提供"下载→校验→安装"的完整链路；已是最新或缺少版本元数据时给出明确说明。
 */
public class UpdateActivity extends BaseActivity {

    private TextView statusView, notesView, progressText;
    private ProgressBar progressBar;
    private Button downloadButton, cancelButton, installButton;
    private View releaseLink;

    private UpdateInfo info;
    private File downloadedApk = null;
    private boolean downloading = false;

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update);
        setPageName("检查更新");

        statusView = findViewById(R.id.update_status);
        notesView = findViewById(R.id.update_notes);
        progressText = findViewById(R.id.update_progress_text);
        progressBar = findViewById(R.id.update_progress);
        downloadButton = findViewById(R.id.btn_download);
        cancelButton = findViewById(R.id.btn_cancel);
        installButton = findViewById(R.id.btn_install);
        releaseLink = findViewById(R.id.update_release_link);

        downloadButton.setOnClickListener(v -> {
            if (info != null && info.isUsable()) startDownload();
            else startCheck(true);   //没有可用更新信息时，下载按钮兼做"重新检查"
        });
        cancelButton.setOnClickListener(v -> UpdateManager.cancelDownload());
        installButton.setOnClickListener(v -> {
            if (downloadedApk != null) UpdateManager.installApk(this, downloadedApk);
        });
        releaseLink.setOnClickListener(v -> {
            if (info != null && !info.releaseUrl.isEmpty())
                LinkUrlUtil.handleWebURL(this, info.releaseUrl);
        });
        findViewById(R.id.update_mirror).setOnClickListener(v -> showMirrorChooser());

        //初始镜像标签：恢复上次选择的更新源
        String prefix = UpdateManager.getMirrorPrefix();
        ((TextView) findViewById(R.id.update_mirror)).setText(
                prefix.isEmpty() ? "更新源：直连（点击切换镜像）" : "更新源：" + hostOf(prefix) + "（点击切换）");

        startCheck(false);
    }

    /**镜像选择：直连 / 内置镜像 / 自定义前缀（只重写 github.com 开头的地址）。*/
    private void showMirrorChooser() {
        final String[] presets = UpdateManager.MIRROR_PRESETS;
        String[] labels = new String[presets.length + 1];
        labels[0] = "直连（GitHub 原始地址）";
        for (int i = 1; i < labels.length; i++) labels[i] = "镜像：" + hostOf(presets[i - 1]);
        labels[presets.length] = "自定义镜像前缀…";

        String current = UpdateManager.getMirrorPrefix();
        int checked = -1;
        for (int i = 0; i < presets.length; i++)
            if (presets[i].equals(current)) checked = i;

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("选择更新源")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    if (which < presets.length) {
                        UpdateManager.setMirrorPrefix(presets[which]);
                        dialog.dismiss();
                        onMirrorChanged();
                    } else {
                        dialog.dismiss();
                        showCustomMirrorInput();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCustomMirrorInput() {
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setPadding(48, 24, 48, 0);
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://你的镜像域名/");
        input.setText(UpdateManager.getMirrorPrefix());
        container.addView(input);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("自定义镜像前缀")
                .setMessage("将拼接在 github.com 链接之前，例如：\nhttps://mirror.example.com/\n留空表示直连")
                .setView(container)
                .setPositiveButton("确定", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty() && !value.startsWith("https://")) {
                        //http 明文前缀会把更新流量暴露给中间人，无 scheme 会直接拼出非法 URL
                        MsgUtil.showMsg("镜像前缀必须是 https:// 开头");
                        return;
                    }
                    UpdateManager.setMirrorPrefix(value);
                    onMirrorChanged();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String hostOf(String prefix) {
        String host = prefix.replaceFirst("^https?://", "");
        int slash = host.indexOf('/');
        return slash == -1 ? host : host.substring(0, slash);
    }

    @SuppressLint("SetTextI18n")
    private void onMirrorChanged() {
        String prefix = UpdateManager.getMirrorPrefix();
        ((TextView) findViewById(R.id.update_mirror)).setText(
                prefix.isEmpty() ? "更新源：直连（点击切换镜像）" : "更新源：" + hostOf(prefix) + "（点击切换）");
        //切换镜像后重查一次，立即验证新源是否可用
        UpdateManager.deleteOldApkFile();
        downloadedApk = null;
        startCheck(true);
    }

    private void startCheck(boolean forceRefresh) {
        statusView.setText("正在检查更新……");
        notesView.setText("");
        releaseLink.setVisibility(View.GONE);
        downloadButton.setVisibility(View.GONE);
        installButton.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        progressText.setVisibility(View.GONE);

        //进入页面时如果自动检查刚拿到过结果就直接用，避免重复请求
        //（镜像切换后的强制重查会传 forceRefresh=true 跳过缓存）
        UpdateInfo cached = forceRefresh ? null : UpdateManager.getCachedInfo();
        if (cached != null && UpdateManager.isNewer(cached)) {
            displayInfo(cached);
            return;
        }

        CenterThreadPool.run(() -> {
            try {
                UpdateInfo latest = UpdateManager.fetchLatest();
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    if (UpdateManager.isNewer(latest)) displayInfo(latest);
                    else if (latest.isUsable()) {
                        statusView.setText("已是最新版本（当前 " + UpdateManager.getInstalledVersionName() + "）");
                        downloadButton.setVisibility(View.VISIBLE);
                        downloadButton.setText("重新检查");
                    } else {
                        //该 release 缺少版本名/安装包（如纯未签名包）：无法自动更新，展示日志与链接
                        statusView.setText("无法自动更新：该发布版缺少版本信息或未提供签名的安装包。\n可查看发布页确认是否有新版本。");
                        displayNotes(latest);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    statusView.setText("检查更新失败：" + e.getMessage() + "\n请检查网络后点击\"重新检查\"");
                    downloadButton.setVisibility(View.VISIBLE);
                    downloadButton.setText("重新检查");
                });
            }
        });
    }

    @SuppressLint("SetTextI18n")
    private void displayInfo(UpdateInfo updateInfo) {
        this.info = updateInfo;
        statusView.setText("发现新版本 " + updateInfo.versionName
                + "\n当前版本：" + UpdateManager.getInstalledVersionName()
                + "，最新版本：" + updateInfo.versionName);

        displayNotes(updateInfo);

        //本版本的安装包若已存在（下载完成后退出页面、网络差时重进等场景），不能只凭
        //exists() 就当"下载完成"——那可能是损坏分片甚至旧版本残留包。先在后台做
        //完整性 + 签名校验，通过才进入安装态；不通过则删包走重新下载。
        File apk = UpdateManager.getApkFile(this, updateInfo);
        if (apk.exists() && apk.length() > 0) {
            statusView.setText(statusView.getText() + "\n\n正在校验已下载的安装包……");
            CenterThreadPool.run(() -> {
                try {
                    UpdateManager.verifyApkIntegrity(this, updateInfo, apk);
                    runOnUiThread(() -> {
                        if (isDestroyed() || isFinishing()) return;
                        downloadedApk = apk;
                        showInstallState();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        if (isDestroyed() || isFinishing()) return;
                        MsgUtil.err(e);
                        downloadButton.setVisibility(View.VISIBLE);
                        downloadButton.setText("重新下载");
                    });
                }
            });
        } else {
            downloadButton.setVisibility(View.VISIBLE);
            downloadButton.setText("下载更新包");
        }
    }

    @SuppressLint("SetTextI18n")
    private void displayNotes(UpdateInfo updateInfo) {
        String notes = updateInfo.notes == null ? "" : updateInfo.notes;
        int metaStart = notes.indexOf("<!--ReBiliTerminal-update");
        if (metaStart != -1) notes = notes.substring(0, metaStart).trim();   //元数据注释不展示给用户
        notesView.setText(notes.isEmpty() ? "（本版本没有更新说明）" : notes);
        releaseLink.setVisibility(View.VISIBLE);
    }

    private void startDownload() {
        downloading = true;
        downloadButton.setVisibility(View.GONE);
        installButton.setVisibility(View.GONE);
        cancelButton.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressText.setVisibility(View.VISIBLE);
        progressText.setText("准备下载……");

        final UpdateInfo updateInfo = info;
        CenterThreadPool.run(() -> {
            try {
                File apk = UpdateManager.downloadApk(this, updateInfo, (downloaded, total) -> runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || !downloading) return;
                    if (total > 0) {
                        progressBar.setIndeterminate(false);
                        progressBar.setProgress((int) (downloaded * 100 / total));
                        progressText.setText(String.format("%.1f MB / %.1f MB",
                                downloaded / 1048576f, total / 1048576f));
                    } else {
                        progressText.setText("已下载 " + String.format("%.1f MB", downloaded / 1048576f));
                    }
                }));
                downloadedApk = apk;
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    downloading = false;
                    showInstallState();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    downloading = false;
                    progressBar.setVisibility(View.GONE);
                    progressText.setVisibility(View.GONE);
                    cancelButton.setVisibility(View.GONE);
                    boolean canceled = e.getMessage() != null && e.getMessage().contains("已取消");
                    if (canceled) MsgUtil.showMsg("已取消下载");
                    else MsgUtil.err(e);
                    if (!canceled) {
                        statusView.setText("下载失败：" + e.getMessage());
                        downloadButton.setVisibility(View.VISIBLE);
                        downloadButton.setText("重新下载");
                    } else {
                        downloadButton.setVisibility(View.VISIBLE);
                        downloadButton.setText("重新下载");
                    }
                });
            }
        });
    }

    private void showInstallState() {
        progressBar.setVisibility(View.GONE);
        progressText.setVisibility(View.GONE);
        cancelButton.setVisibility(View.GONE);
        downloadButton.setVisibility(View.GONE);
        installButton.setVisibility(View.VISIBLE);
        statusView.setText(statusView.getText() + "\n\n下载完成，点击\"安装\"进行更新。");
    }

    @Override
    protected void onDestroy() {
        if (downloading) UpdateManager.cancelDownload();
        super.onDestroy();
    }
}
