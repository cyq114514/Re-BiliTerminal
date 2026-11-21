package com.RobinNotBad.BiliClient.activity.video.local;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.activity.player.AudioPlayerActivity;
import com.RobinNotBad.BiliClient.adapter.video.LocalVideoAdapter;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.model.AudioQueue;
import com.RobinNotBad.BiliClient.model.LocalVideo;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.service.AudioPlayerService;
import com.RobinNotBad.BiliClient.util.AdaptiveDialog;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.FileUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.PickImageUtil;

import java.io.File;
import java.util.ArrayList;

//本地列表
//2023-08-07

public class LocalListActivity extends InstanceActivity {

    /** 二级「听歌模式」：只列纯音频缓存，主缓存页不再混入音频卡 */
    public static final String EXTRA_AUDIO_ONLY = "audio_only";

    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView recyclerView;
    private final ArrayList<LocalVideo> videoList = new ArrayList<>(10);
    /** 全量扫描里的纯音频条目；主页面只把它作为队列来源，二级页才显示 */
    private final ArrayList<LocalVideo> audioList = new ArrayList<>(10);
    private boolean audioOnlyMode;
    private LocalVideoAdapter adapter;
    private TextView emptyTip;

    private boolean started;

    //本地视频播放（2026-11-15 反馈：内置播放器播放本地视频）：SAF 选任意视频文件，
    //解析真实路径后走 TYPE_LOCAL 播放链，与缓存视频同一套内置播放器；不落缓存列表
    private final ActivityResultLauncher<String> pickVideoLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::playPickedVideo);

    /**
     * 降级入口：GetContent 在无 DocumentsUI 的精简 ROM 上会同步抛 ActivityNotFoundException
     * （线上 Z8A/8.1 一条）。这里用 StartActivityForResult 接 ACTION_OPEN_DOCUMENT /
     * MediaStore.ACTION_PICK 的回退结果，同一个 playPickedVideo 处理 uri。
     */
    private final ActivityResultLauncher<Intent> fallbackVideoLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    playPickedVideo(result.getData().getData());
                }
            });

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audioOnlyMode = getIntent().getBooleanExtra(EXTRA_AUDIO_ONLY, false);
        setContentView(R.layout.activity_simple_main_refresh);
        setMenuClick();

        recyclerView = findViewById(R.id.recyclerView);

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setOnRefreshListener(this::refresh);

        emptyTip = findViewById(R.id.emptyTip);

        TextView pageName = findViewById(R.id.pageName);
        pageName.setText(audioOnlyMode ? "听歌模式" : "缓存");

        //音频入口统一收进列表头的「听歌模式」二级页；主缓存页不再放顶部「听歌」chip，
        //避免和二级入口重复，也给标题让出宽度。二级页里才显示「播放全部」。
        View localAudioBtn = findViewById(R.id.local_audio_btn);
        localAudioBtn.setVisibility(View.GONE);
        //本地视频播放入口（2026-11-15 反馈）：SAF 挑一个视频文件直接播；听歌模式页不需要
        View localVideoBtn = findViewById(R.id.local_video_btn);
        localVideoBtn.setVisibility(audioOnlyMode ? View.GONE : View.VISIBLE);
        //听歌 chip 收起后不能继续让「视频」chip 锚在它左边：GONE 锚点会把 chip 甩到屏幕左缘
        if (localVideoBtn.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams vlp = (RelativeLayout.LayoutParams) localVideoBtn.getLayoutParams();
            vlp.removeRule(RelativeLayout.START_OF);
            vlp.removeRule(RelativeLayout.LEFT_OF);
            vlp.addRule(RelativeLayout.START_OF, R.id.timeText);
            vlp.addRule(RelativeLayout.LEFT_OF, R.id.timeText);
            localVideoBtn.setLayoutParams(vlp);
        }
        localVideoBtn.setOnClickListener(v -> {
            //先走统一安全入口；无处理者时继续降级，而不是让 ActivityNotFoundException 杀掉进程
            if (PickImageUtil.launchSafe(pickVideoLauncher, "video/*")) return;
            Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("video/*");
            if (open.resolveActivity(getPackageManager()) != null) {
                fallbackVideoLauncher.launch(open);
                return;
            }
            Intent pick = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            if (pick.resolveActivity(getPackageManager()) != null) {
                fallbackVideoLauncher.launch(pick);
                return;
            }
            MsgUtil.showMsg("此设备没有视频文件选择器\n可先用文件管理器打开，或从缓存列表播放");
        });

        //主页面只剩「选择视频」一颗 chip，听歌模式页没有 chip：标题右留白按模式收窄
        View pageTitle = findViewById(R.id.pageName);
        if (pageTitle != null) {
            android.view.ViewGroup.LayoutParams lp = pageTitle.getLayoutParams();
            if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
                int endDp = audioOnlyMode ? 0 : 80;
                ((android.view.ViewGroup.MarginLayoutParams) lp).setMarginEnd(
                        (int) (endDp * getResources().getDisplayMetrics().density + 0.5f));
            }
        }

        if (!FileUtil.checkStoragePermission()) {
            FileUtil.requestStoragePermission(this);
        }

        CenterThreadPool.run(() -> {
            runOnUiThread(() -> swipeRefreshLayout.setRefreshing(true));
            //先构建到临时列表再并入 videoList：此时 adapter 尚未挂到 UI，主线程不可能并发读
            ArrayList<LocalVideo> initialList = new ArrayList<>(10);
            scan(initialList, FileUtil.getVideoDownloadPath());
            applyScan(initialList);
            adapter = new LocalVideoAdapter(this, videoList, audioOnlyMode);
            //二级页里点纯音频条目=从这条开始连播整个音频队列
            adapter.setOnAudioItemClickListener(position -> startAudioQueue(audioIndexOf(position)));
            if (audioOnlyMode) {
                //二级页列表头「播放全部」：从第一首开始
                adapter.setOnAudioEntryClickListener(() -> startAudioQueue(0));
            } else {
                //主缓存页列表头「听歌模式」：进入只列音频的二级页，不再直接起播
                adapter.setOnAudioEntryClickListener(this::openAudioOnlyPage);
            }

            adapter.setOnLongClickListener(position -> {
                if (position < 0 || position >= videoList.size()) return;
                final LocalVideo target = videoList.get(position);
                //原来靠"4 秒内再长按一次"确认，用户不知道要按第二下，表现为"点好几下没反应"
                //（2026-11-21 反馈：缓存删除反应慢）。改成标准确认框：一次长按 + 一次点按，
                //确认后先把行拿掉给即时反馈，文件在后台删。
                new AdaptiveDialog.Builder(this)
                        .setTitle("删除缓存")
                        .setMessage("将删除「" + target.title + "」的本地文件，确定吗？")
                        .setPositiveButton("删除", (dialog, which) -> deleteLocal(target))
                        .setNegativeButton("取消", null)
                        .show();
            });
            runOnUiThread(() -> {
                recyclerView.setLayoutManager(getLayoutManager());
                recyclerView.setAdapter(adapter);
                swipeRefreshLayout.setRefreshing(false);
                started = true;
                //首载路径此前不查空态：清缓存后首次进入整页空白，只有删除/刷新路径才补查（D-05）
                checkEmpty();
            });
        });
    }

    private void scan(ArrayList<LocalVideo> into, File folder) {
        File[] files = folder.listFiles();
        if (files == null)
            return;

        for (File video : files) {
            if (video.isDirectory()) {
                LocalVideo localVideo = new LocalVideo();
                localVideo.title = video.getName();

                localVideo.cover = (new File(video, "cover.png")).toString();

                localVideo.pageList = new ArrayList<>();
                localVideo.danmakuFileList = new ArrayList<>();
                localVideo.videoFileList = new ArrayList<>();
                localVideo.sizeList = new ArrayList<>();

                File videoFile = new File(video, "video.mp4");
                File audioFile = new File(video, "audio.m4a"); // 仅音频文件
                File danmakuFile = new File(video, "danmaku.xml");

                // 优先检查视频文件，其次检查音频文件
                File mediaFile = videoFile.exists() ? videoFile : (audioFile.exists() ? audioFile : null);

                if (mediaFile != null) {
                    File mark = new File(video, ".DOWNLOADING");
                    if (mark.exists())
                        continue;

                    //只有音频文件、没有 video.mp4：这条要收进二级「听歌模式」，不再混在视频列表
                    localVideo.audioOnly = !videoFile.exists() && audioFile.exists();
                    localVideo.sizeList.add(mediaFile.length());
                    localVideo.videoFileList.add(mediaFile.toString());
                    localVideo.danmakuFileList.add(danmakuFile.toString()); // 单集视频

                    localVideo.calcTotalSize();
                    into.add(localVideo);
                } else {
                    File[] pages = video.listFiles(); // 分页视频
                    if (pages != null) {
                        boolean allAudio = true;
                        for (File page : pages) {
                            if (page.isDirectory()) {
                                File mark = new File(page, ".DOWNLOADING");
                                if (mark.exists())
                                    continue;

                                File pageVideoFile = new File(page, "video.mp4");
                                File pageAudioFile = new File(page, "audio.m4a"); // 仅音频文件
                                File pageDanmakuFile = new File(page, "danmaku.xml");

                                // 优先检查视频文件，其次检查音频文件
                                File pageMediaFile = pageVideoFile.exists() ? pageVideoFile
                                        : (pageAudioFile.exists() ? pageAudioFile : null);

                                if (pageMediaFile != null) {
                                    if (pageMediaFile == pageVideoFile) allAudio = false;
                                    localVideo.pageList.add(page.getName());
                                    localVideo.sizeList.add(pageMediaFile.length());
                                    localVideo.videoFileList.add(pageMediaFile.toString());
                                    localVideo.danmakuFileList.add(pageDanmakuFile.toString());
                                }
                            }
                        }
                        localVideo.audioOnly = allAudio && !localVideo.videoFileList.isEmpty();
                        localVideo.calcTotalSize();
                        if (localVideo.videoFileList.size() > 0)
                            into.add(localVideo);
                    }
                }
            }
        }
    }

    /** 按 audioOnly 拆成视频/音频两张表；二级页把音频表当主列表。 */
    private void applyScan(ArrayList<LocalVideo> all) {
        videoList.clear();
        audioList.clear();
        for (LocalVideo item : all) {
            if (item == null) continue;
            if (item.audioOnly) audioList.add(item);
            else videoList.add(item);
        }
        if (audioOnlyMode) {
            videoList.clear();
            videoList.addAll(audioList);
        }
    }

    /** 主缓存页 → 二级「听歌模式」页（只列音频）。 */
    private void openAudioOnlyPage() {
        if (audioList.isEmpty()) {
            MsgUtil.showMsg("还没有缓存音频");
            return;
        }
        Intent intent = new Intent(this, LocalListActivity.class);
        intent.putExtra(EXTRA_AUDIO_ONLY, true);
        startActivity(intent);
    }

    /**
     * 把缓存列表展开成音频队列条目：单集 1 条，多P 每 P 一条，顺序与列表一致。
     *
     * <p>只负责组装，不决定从哪首开始——听歌按钮传 0，点某条音频则传该条的展开下标。
     * 主页面从 audioList 取，二级页 videoList 本身就是音频表；顺带过滤掉混在
     * 多P 目录里的 video.mp4，避免听歌队列里混进视频文件。
     */
    private ArrayList<VideoCard> buildAudioItems() {
        ArrayList<VideoCard> items = new ArrayList<>();
        ArrayList<LocalVideo> source = audioOnlyMode ? videoList : audioList;
        for (LocalVideo localVideo : source) {
            for (int i = 0; i < localVideo.videoFileList.size(); i++) {
                String path = localVideo.videoFileList.get(i);
                if (!path.endsWith("audio.m4a")) continue;
                VideoCard card = new VideoCard();
                //单集标题=文件夹名；分P 标题=文件夹名·分P 名，与本地选集页口径一致
                boolean single = localVideo.videoFileList.size() == 1;
                card.title = single ? localVideo.title
                        : localVideo.title + " · " + localVideo.pageList.get(i);
                card.upName = "本地缓存";
                card.cover = localVideo.cover;
                card.localPath = path;
                items.add(card);
            }
        }
        return items;
    }

    /** 从展开后的第 startIndex 条起播，并打开全屏听歌页 */
    /**
     * SAF 选中的视频 → 播放。本应用 targetSdk26 + 页内已申请存储权限，
     * MediaProvider 对 legacy 应用仍会下发 _data 真实路径，内置播放器直接吃路径；
     * 个别云盘/第三方文档给不出 _data，此时提示换来源，不硬塞一个假路径给播放器。
     */
    private void playPickedVideo(Uri uri) {
        if (uri == null) return;
        CenterThreadPool.run(() -> {
            try {
                String path = null;
                String name = null;
                Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                if (cursor != null) {
                    try {
                        if (cursor.moveToFirst()) {
                            int dataIdx = cursor.getColumnIndex("_data");
                            if (dataIdx >= 0) path = cursor.getString(dataIdx);
                            int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            if (nameIdx >= 0) name = cursor.getString(nameIdx);
                        }
                    } finally {
                        cursor.close();
                    }
                }
                if (path == null || path.isEmpty()) {
                    runOnUiThread(() -> MsgUtil.showMsg("该来源不支持直接播放，请从相册或本机文件里选"));
                    return;
                }
                if (name == null || name.isEmpty()) {
                    int cut = path.lastIndexOf('/');
                    name = cut >= 0 ? path.substring(cut + 1) : path;
                }
                final String mediaPath = path;
                final String title = name;
                runOnUiThread(() -> {
                    try {
                        PlayerData playerData = new PlayerData(PlayerData.TYPE_LOCAL);
                        playerData.videoUrl = mediaPath;
                        playerData.title = title;
                        startActivity(PlayerApi.jumpToPlayer(playerData));
                    } catch (Exception e) {
                        MsgUtil.showMsg("播放失败: " + e.getClass().getSimpleName());
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> MsgUtil.showMsg("播放失败: " + e.getClass().getSimpleName()));
            }
        });
    }

    private void startAudioQueue(int startIndex) {
        ArrayList<VideoCard> items = buildAudioItems();
        if (items.isEmpty()) {
            MsgUtil.showMsg("还没有可听的缓存");
            return;
        }
        if (startIndex < 0 || startIndex >= items.size()) startIndex = 0;
        //mediaId 用 -1：不与任何收藏夹冲突，续播记录天然隔离；
        //同时 AudioQueue 据此判定"没有下一页"，播完最后一首会正常回卷到第一首
        AudioPlayerService.startQueue(this, items, -1, "", "本地缓存", startIndex, 0);
        if (AudioQueue.get().current() == null) {
            //队列被过滤空时不要打开听歌页——那里只会弹"没有正在播放的音频"，反而看不出原因
            MsgUtil.showMsg("这些缓存无法加入播放队列");
            return;
        }
        startActivity(new Intent(this, AudioPlayerActivity.class));
    }

    /** 第 videoIndex 个缓存文件夹在展开后的音频条目里的起始下标 */
    private int audioIndexOf(int videoIndex) {
        int index = 0;
        for (int i = 0; i < videoIndex && i < videoList.size(); i++)
            index += videoList.get(i).videoFileList.size();
        return index;
    }

    /** 删除一条缓存：先把行拿掉给即时反馈，文件在后台删；删失败再按磁盘真实内容刷回来 */
    private void deleteLocal(final LocalVideo target) {
        final int index = videoList.indexOf(target);
        if (index < 0) return;
        final File file = new File(FileUtil.getVideoDownloadPath(), target.title);
        videoList.remove(index);
        if (adapter != null) {
            adapter.notifyItemRemoved(index + 1);
            adapter.notifyItemRangeChanged(index + 1, videoList.size() - index);
        }
        checkEmpty();
        CenterThreadPool.run(() -> {
            FileUtil.deleteFolder(file);
            final boolean gone = !file.exists();
            runOnUiThread(() -> {
                MsgUtil.showMsg(gone ? "已删除" : "删除失败，请检查存储权限");
                if (!gone) refresh();
            });
        });
    }

    private void checkEmpty() {
        runOnUiThread(() -> {
            //空态已收进列表头（cell_local_header.local_empty），外部 emptyTip 永远隐藏，
            //避免 centerVertical 的文字和固定的下载/听歌入口在屏幕中间叠在一起
            if (emptyTip != null) emptyTip.setVisibility(View.GONE);
        });
    }

    public void refresh() {
        if (started)
            CenterThreadPool.run(() -> {
                runOnUiThread(() -> swipeRefreshLayout.setRefreshing(true));
                //后台线程直改 videoList 会与 UI 线程的 onBindViewHolder 并发读写同一 ArrayList（可崩溃），
                //且 notifyItemRangeChanged 表达不了增删——改为后台构建新列表、主线程整体替换后全量刷新
                ArrayList<LocalVideo> newList = new ArrayList<>(10);
                scan(newList, FileUtil.getVideoDownloadPath());
                runOnUiThread(() -> {
                    applyScan(newList);
                    if (adapter != null) adapter.notifyDataSetChanged();
                    swipeRefreshLayout.setRefreshing(false);
                    checkEmpty();
                });
            });
    }
}