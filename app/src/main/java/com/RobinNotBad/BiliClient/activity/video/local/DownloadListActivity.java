package com.RobinNotBad.BiliClient.activity.video.local;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity;
import com.RobinNotBad.BiliClient.adapter.video.DownloadAdapter;
import com.RobinNotBad.BiliClient.listener.OnItemClickListener;
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener;
import com.RobinNotBad.BiliClient.model.DownloadSection;
import com.RobinNotBad.BiliClient.service.DownloadService;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.FileUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

public class DownloadListActivity extends RefreshListActivity {
    public static WeakReference<DownloadListActivity> weakRef;
    DownloadAdapter adapter;
    Timer timer;
    boolean emptyTipShown;
    boolean firstRefresh = true;
    boolean created;
    ArrayList<DownloadSection> sections;
    private float lastPercent = -1;
    private String lastState = null;
    private long lastDownloadingId = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setPageName("下载列表");
        setRefreshing(false);
        weakRef = new WeakReference<>(this);

        CenterThreadPool.run(() -> {
            created = true;
            refreshList(false);

            timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (adapter == null || !created || isDestroyed())
                        return;
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
                        CenterThreadPool.run(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (sections == null || position < 0 || position >= sections.size())
                                        return;

                                    final DownloadSection delete = sections.get(position);
                                    if (delete == null)
                                        return;

                                    if (delete.state.equals("downloading") && DownloadService.started) {
                                        stopService(new Intent(DownloadListActivity.this, DownloadService.class));
                                        try {
                                            Thread.sleep(500);
                                        } catch (InterruptedException ignored) {
                                        }
                                    }

                                    File folder = delete.getPath();
                                    if (folder != null && folder.exists()) {
                                        FileUtil.deleteFolder(folder);
                                    }

                                    DownloadService.deleteSection(delete.id);

                                    refreshList(false);
                                    MsgUtil.showMsg("删除成功");
                                } catch (Exception e) {
                                    MsgUtil.err(e);
                                }
                            }
                        });
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
        if (timer != null)
            timer.cancel();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        weakRef = null;
        super.onDestroy();
    }
}
