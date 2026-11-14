package com.RobinNotBad.BiliClient.activity.video.local;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.video.DownloadAdapter;
import com.RobinNotBad.BiliClient.listener.OnItemClickListener;
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener;
import com.RobinNotBad.BiliClient.model.DownloadSection;
import com.RobinNotBad.BiliClient.service.DownloadService;
import com.RobinNotBad.BiliClient.util.AdaptiveDialog;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.FileUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class DownloadListActivity extends RefreshListActivity {
    public static WeakReference<DownloadListActivity> weakRef;
    DownloadAdapter adapter;
    Timer timer;
    boolean emptyTipShown;
    boolean firstRefresh = true;
    boolean created;
    //Timer 在后台线程创建：若页面在创建前就被销毁，onDestroy 里 cancel 到的是 null，
    //随后才 new 出来的 Timer 会永久 500ms 空转并强引用已销毁的 Activity（View 树一起泄漏）
    volatile boolean destroyed;
    ArrayList<DownloadSection> sections;
    private float lastPercent = -1;
    private String lastState = null;
    private long lastDownloadingId = -1;
    //批量删除
    private TextView btnBatch;
    private boolean managing;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setPageName("下载列表");
        setRefreshing(false);
        weakRef = new WeakReference<>(this);

        //顶栏「批量」入口：布局里默认 GONE（activity_simple_refresh 是共用顶栏），只在本页显形
        btnBatch = findViewById(R.id.btn_batch);
        if (btnBatch != null) {
            btnBatch.setVisibility(View.VISIBLE);
            btnBatch.setOnClickListener(v -> toggleManage());
        }

        CenterThreadPool.run(() -> {
            if (destroyed) return;
            created = true;
            refreshList(false);
            if (destroyed) return;

            Timer newTimer = new Timer();
            timer = newTimer;
            //赋值后复核销毁位，闭合"onDestroy 早于本次创建"的竞态：此时 onDestroy 已经 cancel 过 null，
            //只有这里主动取消，Timer 才不会变成无人认领的孤儿线程
            if (destroyed) {
                newTimer.cancel();
                return;
            }
            newTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (adapter == null || !created || isDestroyed() || destroyed) {
                        this.cancel();
                        return;
                    }
                    if (DownloadService.section != null) {
                        boolean needUpdate = false;
                        if (lastDownloadingId != DownloadService.section.id) {
                            lastDownloadingId = DownloadService.section.id;
                            needUpdate = true;
                        }
                        if (lastPercent != DownloadService.percent) {
                            lastPercent = DownloadService.percent;
                            needUpdate = true;
                        }
                        if (lastState == null || !lastState.equals(DownloadService.state)) {
                            lastState = DownloadService.state;
                            needUpdate = true;
                        }
                        if (needUpdate) {
                            final int pos = findDownloadingPosition();
                            if (pos >= 0) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        adapter.notifyItemChanged(pos);
                                    }
                                });
                            }
                        }
                    }
                }
            }, 300, 500);
        });

    }

    private int findDownloadingPosition() {
        if (sections == null || DownloadService.section == null)
            return -1;
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).id == DownloadService.section.id) {
                return i;
            }
        }
        return -1;
    }

    //确认框点「删除」后的实际删除动作：原长按直删逻辑原样迁移（后台线程执行）
    private void deleteSection(final DownloadSection delete) {
        try {
            if (delete.state.equals("downloading") && DownloadService.started) {
                stopService(new Intent(DownloadListActivity.this, DownloadService.class));
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }

            //type 脏数据（非 video_*）时 getPath 会回退到相册目录，
            //不加类型校验会递归删光整个"哔哩终端"图片目录
            if (delete.type != null && delete.type.contains("video")) {
                File folder = delete.getPath();
                if (folder != null && folder.exists()) {
                    FileUtil.deleteFolder(folder);
                }
            }

            DownloadService.deleteSection(delete.id);

            refreshList(false);
            MsgUtil.showMsg("删除成功");
        } catch (Exception e) {
            MsgUtil.err(e);
        }
    }

    //------------------------------------------------------------ 批量删除

    /**
     * 「批量」按钮的三态：
     * 未进入 → 进入批量模式；已进入且没选 → 退出；已进入且有选中 → 二次确认后批量删除。
     * 只用一个顶栏按钮，避免在小屏/手表上再挤一个「删除」入口。
     */
    private void toggleManage() {
        if (adapter == null) {
            MsgUtil.showMsg("列表还没加载好");
            return;
        }
        if (!managing) {
            if (sections == null || sections.isEmpty()) {
                MsgUtil.showMsg("没有可删除的下载");
                return;
            }
            managing = true;
            adapter.setSelectionMode(true);
            updateBatchButton(0);
            MsgUtil.showMsg("点选要删除的下载，选完点「删除」");
            return;
        }
        final int count = adapter.getSelectedCount();
        if (count == 0) {
            exitManage();
            return;
        }
        new AdaptiveDialog.Builder(this)
                .setTitle("批量删除")
                .setMessage("将删除选中的 " + count + " 个下载及其本地文件，此操作不可恢复。")
                .setPositiveButton("删除", (dialog, which) -> {
                    //先取选中快照再退出管理模式（退出会清空选中集合）
                    final List<Long> ids = adapter.getSelectedIds();
                    exitManage();
                    CenterThreadPool.run(() -> deleteSectionsByIds(ids));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void exitManage() {
        managing = false;
        if (adapter != null) adapter.setSelectionMode(false);
        updateBatchButton(0);
    }

    private void updateBatchButton(int count) {
        if (btnBatch == null) return;
        if (!managing) btnBatch.setText("批量");
        else if (count == 0) btnBatch.setText("取消");
        else btnBatch.setText("删除(" + count + ")");
    }

    /**
     * 批量删除：把单条删除的既有动作（停服务 → 按类型删文件夹 → 删记录）循环一遍，
     * 只把「每删一条就刷一次列表」提到最后统一做一次。单条的 {@link #deleteSection} 不变，
     * 长按删除仍走原链路。
     */
    private void deleteSectionsByIds(List<Long> ids) {
        try {
            List<DownloadSection> targets = new ArrayList<>();
            if (sections != null) {
                for (DownloadSection s : sections) {
                    if (ids.contains(s.id)) targets.add(s);
                }
            }
            if (targets.isEmpty()) return;

            //有正在下载的项：先停服务再删，避免删到一半后台还在往里写文件
            boolean hasDownloading = false;
            for (DownloadSection s : targets) {
                if ("downloading".equals(s.state) && DownloadService.started) {
                    hasDownloading = true;
                    break;
                }
            }
            if (hasDownloading) {
                stopService(new Intent(DownloadListActivity.this, DownloadService.class));
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }

            int ok = 0;
            for (DownloadSection s : targets) {
                try {
                    //同 deleteSection 的类型校验：脏数据下 getPath 会回退到相册目录
                    if (s.type != null && s.type.contains("video")) {
                        File folder = s.getPath();
                        if (folder != null && folder.exists()) {
                            FileUtil.deleteFolder(folder);
                        }
                    }
                    DownloadService.deleteSection(s.id);
                    ok++;
                } catch (Exception e) {
                    Logu.e("download", "批量删除失败：" + s.id + " " + e);
                }
            }

            refreshList(false);
            final int deleted = ok;
            runOnUiThread(() -> MsgUtil.showMsg("已删除 " + deleted + " 项"));
        } catch (Exception e) {
            MsgUtil.err(e);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void refreshList(boolean fromOutside) {
        if (this.isDestroyed() || !created)
            return;
        Log.d("debug", "刷新下载列表");

        sections = DownloadService.getAll();

        if (sections == null || sections.isEmpty()) {
            if (!emptyTipShown) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        MsgUtil.showMsg("下载列表为空");
                        showEmptyView();
                    }
                });
                emptyTipShown = true;
            }
        } else {
            for (DownloadSection s : sections) {
                Log.d("debug-download", s.name_short);
            }

            if (emptyTipShown) {
                emptyTipShown = false;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        hideEmptyView();
                    }
                });
            }

            if (firstRefresh) {
                adapter = new DownloadAdapter(DownloadListActivity.this, sections);
                //勾选数量变化 → 顶栏按钮文字在「取消」与「删除(N)」之间切换
                adapter.setOnSelectionChangedListener(count ->
                        runOnUiThread(() -> updateBatchButton(count)));
                adapter.setOnClickListener(new OnItemClickListener() {
                    @Override
                    public void onItemClick(int position) {
                        CenterThreadPool.run(new Runnable() {
                            @Override
                            public void run() {
                                Log.d("debug-download", "click:" + position);
                                if (sections == null || position < 0 || position >= sections.size())
                                    return;

                                DownloadSection section = sections.get(position);
                                if (section.state.equals("downloading")) {
                                    MsgUtil.showMsg("下载中，无法操作");
                                    return;
                                }
                                if (section.state.equals("error")) {
                                    DownloadService.setState(section.id, "none");
                                }

                                DownloadService.start(section.id);
                            }
                        });
                    }
                });

                adapter.setOnLongClickListener(new OnItemLongClickListener() {
                    @Override
                    public void onItemLongClick(int position) {
                        if (sections == null || position < 0 || position >= sections.size())
                            return;
                        final DownloadSection delete = sections.get(position);
                        if (delete == null)
                            return;
                        //删除的是本地文件+下载记录，不可恢复：先弹确认框，
                        //误触长按不再直接删（此前无任何确认，双长按口径也到不了这里）
                        new AdaptiveDialog.Builder(DownloadListActivity.this)
                                .setTitle("删除下载")
                                .setMessage("将删除「" + delete.title + "」的已下载文件，确定吗？")
                                .setPositiveButton("删除", (dialog, which) ->
                                        CenterThreadPool.run(() -> deleteSection(delete)))
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setAdapter(adapter);
                    }
                });
                firstRefresh = false;
            } else {
                adapter.downloadList = sections;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        adapter.notifyDataSetChanged();
                    }
                });
                Log.d("debug-adapter", String.valueOf(adapter.getItemCount()));
            }
        }

    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (timer != null)
            timer.cancel();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        weakRef = null;
        super.onDestroy();
    }
}
