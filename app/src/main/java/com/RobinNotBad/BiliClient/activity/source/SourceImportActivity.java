package com.RobinNotBad.BiliClient.activity.source;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.source.SourceManager;
import com.RobinNotBad.BiliClient.source.SourceStore;
import com.RobinNotBad.BiliClient.source.SourceSubscriptions;
import com.RobinNotBad.BiliClient.source.model.SourceException;
import com.RobinNotBad.BiliClient.util.MsgUtil;

/**
 * 订阅导入：地址导入 / 粘贴 JSON。
 *
 * <p>两条路径最终都走 {@link SourceSubscriptions#parse}，所以「支持哪些格式」只有一处真相：
 * Animeko 导出文件、订阅文件、单条 arguments 粘贴都能吃。
 *
 * <p>不内置任何规则清单：规则内容由用户自行填入订阅地址（版权与合规，见方案 §6/§8）。
 */
public class SourceImportActivity extends InstanceActivity {

    private EditText urlInput;
    private EditText jsonInput;
    private TextView statusText;
    private TextView btnFetch;
    private TextView btnParse;
    private boolean importing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_source_import);
        setTopbarExit();
        setPageName(getString(R.string.source_import_title));

        urlInput = findViewById(R.id.urlInput);
        jsonInput = findViewById(R.id.jsonInput);
        statusText = findViewById(R.id.statusText);
        btnFetch = findViewById(R.id.btnFetch);
        btnParse = findViewById(R.id.btnParse);

        btnFetch.setOnClickListener(view -> importFromUrl());
        btnParse.setOnClickListener(view -> importFromJson());
        statusText.setText(R.string.source_import_idle);
    }

    private void importFromUrl() {
        if (importing) return;
        final String url = urlInput.getText() == null ? "" : urlInput.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            MsgUtil.showMsg("请填写订阅地址");
            return;
        }
        setImporting(true, getString(R.string.source_import_fetching));
        SourceManager.get().pool().execute(() -> {
            try {
                String content = SourceSubscriptions.fetch(url);
                apply(content);
            } catch (SourceException e) {
                finishWithError(e.userMessage());
            } catch (Throwable t) {
                finishWithError("导入失败：" + t.getClass().getSimpleName());
            }
        });
    }

    private void importFromJson() {
        if (importing) return;
        String json = jsonInput.getText() == null ? "" : jsonInput.getText().toString();
        if (TextUtils.isEmpty(json.trim())) {
            MsgUtil.showMsg("请粘贴订阅 JSON");
            return;
        }
        setImporting(true, getString(R.string.source_import_parsing));
        apply(json);
    }

    /**
     * 解析 + 落盘 + 汇报。
     *
     * <p>解析与写盘都放后台：一次订阅几十条、原文上百 KB，JSON 解析 + SP 写入在手表上
     * 足以卡出一帧以上的掉帧。
     */
    private void apply(final String content) {
        SourceManager.get().pool().execute(() -> {
            final SourceSubscriptions.Parsed parsed = SourceSubscriptions.parse(content);
            if (!parsed.ok()) {
                finishWithError(parsed.error);
                return;
            }
            final int added = SourceStore.upsert(parsed.sources);
            SourceManager.get().clearSearchCache();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                setImporting(false, "");
                String message = parsed.summary() + "，新增 " + added + " 条";
                statusText.setText(message);
                MsgUtil.showMsgLong(message);
                finish();
            });
        });
    }

    private void finishWithError(final String reason) {
        runOnUiThread(() -> {
            if (isFinishing()) return;
            setImporting(false, "");
            String message = reason == null || reason.isEmpty() ? "导入失败" : reason;
            statusText.setText(message);
            MsgUtil.showMsgLong(message);
        });
    }

    private void setImporting(boolean running, String hint) {
        this.importing = running;
        btnFetch.setEnabled(!running);
        btnParse.setEnabled(!running);
        btnFetch.setAlpha(running ? 0.5f : 1f);
        btnParse.setAlpha(running ? 0.5f : 1f);
        if (!TextUtils.isEmpty(hint)) statusText.setText(hint);
    }
}
