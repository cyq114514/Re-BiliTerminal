package com.RobinNotBad.BiliClient.activity.source;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.adapter.source.SourceInfoAdapter;
import com.RobinNotBad.BiliClient.source.SourceDefaults;
import com.RobinNotBad.BiliClient.source.SourceManager;
import com.RobinNotBad.BiliClient.source.SourceStore;
import com.RobinNotBad.BiliClient.source.model.SourceInfo;
import com.RobinNotBad.BiliClient.source.resolve.WebViewSniffer;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import com.RobinNotBad.BiliClient.util.AdaptiveDialog;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.util.List;

/**
 * 数据源管理：启停、调序、删除、导入、网页解析开关。
 *
 * <p>行点击弹动作列表而不是在行内塞 4 个按钮：手表上 320px 宽的行放不下 4 个可点区域，
 * 塞得下也会误触。
 */
public class SourceListActivity extends InstanceActivity {

    private SourceInfoAdapter adapter;
    private RecyclerView recycler;
    private TextView countText;
    private TextView emptyTip;
    private TextView btnSniff;
    private TextView btnRestore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_source_list);
        setTopbarExit();
        setPageName(getString(R.string.source_list_title));

        recycler = findViewById(R.id.recycler);
        countText = findViewById(R.id.countText);
        emptyTip = findViewById(R.id.emptyTip);
        btnSniff = findViewById(R.id.btnSniff);
        btnRestore = findViewById(R.id.btnRestore);

        adapter = new SourceInfoAdapter();
        adapter.setOnItemClickListener(this::showActions);
        recycler.setLayoutManager(new CustomLinearManager(this));
        recycler.setAdapter(adapter);

        findViewById(R.id.btnImport).setOnClickListener(view ->
                startActivity(new Intent(this, SourceImportActivity.class)));
        btnSniff.setOnClickListener(view -> toggleSniff());
        btnRestore.setOnClickListener(view -> confirmRestore());

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void toggleSniff() {
        boolean next = !SourceManager.get().sniffEnabled();
        SourceManager.get().setSniffEnabled(next);
        renderSniff();
        if (next && !WebViewSniffer.available()) {
            MsgUtil.showMsgLong("本设备没有可用的 WebView，网页解析可能无效");
        }
    }

    private void renderSniff() {
        boolean enabled = SourceManager.get().sniffEnabled();
        btnSniff.setText(enabled ? R.string.source_sniff_on : R.string.source_sniff_off);
    }

    private void refresh() {
        renderSniff();
        //启停/排序/删除后旧搜索结果不再代表当前配置
        SourceManager.get().clearSearchCache();
        List<SourceInfo> sources = SourceStore.load();
        adapter.update(sources);
        int enabled = 0;
        for (SourceInfo info : sources) if (info.enabled) enabled++;
        countText.setText(getString(R.string.source_list_count, sources.size(), enabled));
        boolean empty = sources.isEmpty();
        emptyTip.setVisibility(empty ? View.VISIBLE : View.GONE);
        recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void showActions(final SourceInfo info) {
        final String[] items = new String[]{
                info.enabled ? getString(R.string.source_action_disable) : getString(R.string.source_action_enable),
                getString(R.string.source_action_move_up),
                getString(R.string.source_action_move_down),
                getString(R.string.source_action_delete)
        };
        new AdaptiveDialog.Builder(this)
                .setTitle(info.displayName())
                .setMessage(info.lastError == null || info.lastError.isEmpty()
                        ? getString(R.string.source_action_hint)
                        : getString(R.string.source_action_last_error, info.lastError))
                //单选列表点击只回调、不自动关闭（与系统行为一致），所以由这里 dismiss
                .setSingleChoiceItems(items, -1, (dialog, which) -> {
                    dialog.dismiss();
                    handleAction(info, which);
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void handleAction(SourceInfo info, int position) {
        switch (position) {
            case 0:
                boolean next = !info.enabled;
                SourceStore.setEnabled(info.id, next);
                MsgUtil.showMsg(next ? "已启用" : "已停用");
                break;
            case 1:
                if (!SourceStore.move(info.id, -1)) MsgUtil.showMsg("已经在最前面");
                break;
            case 2:
                if (!SourceStore.move(info.id, 1)) MsgUtil.showMsg("已经在最后面");
                break;
            case 3:
                confirmDelete(info);
                return;
            default:
                return;
        }
        refresh();
    }

    private void confirmDelete(final SourceInfo info) {
        new AdaptiveDialog.Builder(this)
                .setTitle(R.string.source_delete_title)
                .setMessage(getString(R.string.source_delete_message, info.displayName()))
                .setPositiveButton(R.string.source_delete_confirm, (dialog, which) -> {
                    SourceStore.remove(info.id);
                    MsgUtil.showMsg("已删除");
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 恢复内置默认源。
     *
     * <p>与首次启动的自动播种分开：自动播种只跑一次（用户删空后不再强塞），
     * 这里由用户显式触发，读的是同一份 assets 快照。
     */
    private void confirmRestore() {
        new AdaptiveDialog.Builder(this)
                .setTitle(R.string.source_restore_title)
                .setMessage(R.string.source_restore_message)
                .setPositiveButton(R.string.source_restore_short, (dialog, which) -> restoreDefaults())
                .setNegativeButton("取消", null)
                .show();
    }

    private void restoreDefaults() {
        //解析 170KB JSON + 写 SP 不放在主线程：手表上能明显卡一下
        CenterThreadPool.run(() -> {
            final int added = SourceDefaults.restoreDefaults();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (added < 0) {
                    MsgUtil.showMsg(getString(R.string.source_restore_failed));
                    return;
                }
                MsgUtil.showMsg(getString(R.string.source_restore_done, added));
                refresh();
            });
        });
    }
}
