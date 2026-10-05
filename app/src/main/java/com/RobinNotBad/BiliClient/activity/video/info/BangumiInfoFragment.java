package com.RobinNotBad.BiliClient.activity.video.info;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.ImageViewerActivity;
import com.RobinNotBad.BiliClient.activity.settings.SettingPlayerChooseActivity;
import com.RobinNotBad.BiliClient.activity.video.JumpToPlayerActivity;
import com.RobinNotBad.BiliClient.adapter.video.EpisodeCardAdapter;
import com.RobinNotBad.BiliClient.adapter.video.SeasonTabAdapter;
import com.RobinNotBad.BiliClient.api.BangumiApi;
import com.RobinNotBad.BiliClient.api.HistoryApi;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.model.Bangumi;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import androidx.lifecycle.MutableLiveData;

import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.Result;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BangumiInfoFragment extends Fragment {
    private long mediaId;
    private int selectedSection = 0, selectedEpisode = 0;
    private Dialog dialog;
    private View rootView;
    private RecyclerView episodeRecyclerView;
    private RecyclerView seasonTabsRecyclerView;
    private TextView episodeStatus;
    private Bangumi bangumi;
    private EpisodeCardAdapter episodeCardAdapter;
    private SeasonTabAdapter seasonTabAdapter;

    //季 tab 运行时状态（官方式页内切季）：
    private long currentSeasonId;          //当前展示的季
    private boolean switchingSeason = false; //切季请求防抖
    //各季数据缓存：seasonId → 分区列表/季类型/状态文案/上次看到 epid
    private final Map<Long, BangumiApi.SeasonMeta> seasonCache = new HashMap<>();
    //各季离开时的选中位置记忆：seasonId → {sectionIdx, epIdx}
    private final Map<Long, int[]> seasonSelection = new HashMap<>();

    public static BangumiInfoFragment newInstance(long mediaId) {
        Bundle args = new Bundle();
        args.putLong("media_id", mediaId);
        BangumiInfoFragment fragment = new BangumiInfoFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Bundle arguments = getArguments();
        if (arguments != null) {
            mediaId = arguments.getLong("media_id");
        }
        rootView = inflater.inflate(R.layout.fragment_media_info, container, false);
        return rootView;
    }

    @Override
    public void onDestroyView() {
        //视图销毁时清引用：Fragment 回退栈里残留的 rootView 会钉住整棵 View 树；
        //选集弹窗未 dismiss 会触发 WindowLeaked
        if (dialog != null) {
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (Exception ignored) {
            }
            dialog = null;
        }
        rootView = null;
        super.onDestroyView();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        view.setVisibility(View.GONE);
        episodeRecyclerView = rootView.findViewById(R.id.rv_episode_list);
        //用 MutableLiveData+CenterThreadPool.run 替代 supplyAsyncWithLiveData，
        //避免 fetch 异常（如 B站返回 HTML）被 supplyAsyncWithLiveData 内部 MsgUtil.err 弹对话框
        MutableLiveData<Result<Bangumi>> liveData = new MutableLiveData<>();
        CenterThreadPool.run(() -> {
            try {
                liveData.postValue(Result.success(BangumiApi.getBangumi(mediaId)));
            } catch (Exception e) {
                liveData.postValue(Result.failure(e));
            }
        });
        liveData.observe(getViewLifecycleOwner(), (result) -> result.onSuccess((bangumi) -> {
            this.bangumi = bangumi;
            initView();
            checkFollowStatus();
        }).onFailure((error) -> {
                    MsgUtil.showMsgLong("番剧信息获取失败！\n可能已下架？");
                    Logu.e("BangumiInfoFragment", "getBangumi failed: " + error.getMessage());
                    if (isAdded() && getActivity() != null && !getActivity().isFinishing()) {
                        getActivity().finish();
                    }
                }));
    }

    @SuppressLint("SetTextI18n")
    private void initView() {
        //init data.
        ImageView imageMediaCover = rootView.findViewById(R.id.image_media_cover);
        Button playButton = rootView.findViewById(R.id.btn_play);
        Button followButton = rootView.findViewById(R.id.btn_follow);
        //初始状态用 SharedPreferences 缓存判断（追番列表加载时已缓存 media_id/season_id）
        long sid = bangumi.info != null ? bangumi.info.season_id : 0;
        followButton.setText(SharedPreferencesUtil.getBoolean("bangumi_follow_" + sid, false) ? "已追番" : "追番");
        followButton.setOnClickListener(v -> toggleFollow(followButton));
        TextView title = rootView.findViewById(R.id.text_title);
        TextView subtitle = rootView.findViewById(R.id.text_subtitle);
        TextView areaType = rootView.findViewById(R.id.text_area_type);
        TextView rating = rootView.findViewById(R.id.text_rating);
        TextView pubTime = rootView.findViewById(R.id.text_pub_time);
        TextView stats = rootView.findViewById(R.id.text_stats);
        TextView styles = rootView.findViewById(R.id.text_styles);
        View evaluateHeader = rootView.findViewById(R.id.layout_evaluate_header);
        ImageView evaluateArrow = rootView.findViewById(R.id.icon_evaluate_arrow);
        TextView evaluate = rootView.findViewById(R.id.text_evaluate);
        View staffHeader = rootView.findViewById(R.id.layout_staff_header);
        ImageView staffArrow = rootView.findViewById(R.id.icon_staff_arrow);
        TextView staff = rootView.findViewById(R.id.text_staff);
        TextView record = rootView.findViewById(R.id.text_record);
        episodeStatus = rootView.findViewById(R.id.episode_status);
        seasonTabsRecyclerView = rootView.findViewById(R.id.rv_season_tabs);
        selectedSection = 0;

        rootView.setVisibility(View.GONE);

        Glide.with(requireContext())
                .load(GlideUtil.url(bangumi.info.cover_horizontal))
                .transition(GlideUtil.getTransitionOptions())
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .placeholder(R.mipmap.placeholder)
                .into(imageMediaCover);
        imageMediaCover.setOnClickListener((view) -> startActivity(new Intent(view.getContext(), ImageViewerActivity.class).putExtra("imageList", new ArrayList<>(java.util.Collections.singletonList(bangumi.info.cover_horizontal)))));
        title.setText(bangumi.info.title);

        // 副标题
        if (bangumi.info.subtitle != null && !bangumi.info.subtitle.isEmpty()) {
            subtitle.setText(bangumi.info.subtitle);
            subtitle.setVisibility(View.VISIBLE);
        } else {
            subtitle.setVisibility(View.GONE);
        }

        // 地区和类型
        String areaTypeText = (bangumi.info.area_name != null ? bangumi.info.area_name : "") +
                             (bangumi.info.type_name != null ? " | " + bangumi.info.type_name : "");
        if (!areaTypeText.trim().isEmpty()) {
            areaType.setText(areaTypeText.trim());
            areaType.setVisibility(View.VISIBLE);
        } else {
            areaType.setVisibility(View.GONE);
        }

        // 评分
        if (bangumi.info.score > 0) {
            rating.setText(String.format("评分：%.1f (%d人)", bangumi.info.score, bangumi.info.count));
            rating.setVisibility(View.VISIBLE);
        } else {
            rating.setVisibility(View.GONE);
        }

        // 发布时间
        if (bangumi.info.publish != null && bangumi.info.publish.pub_time_show != null && !bangumi.info.publish.pub_time_show.isEmpty()) {
            String status = bangumi.info.publish.is_finish == 1 ? "已完结" : "连载中";
            pubTime.setText(bangumi.info.publish.pub_time_show + " " + status);
            pubTime.setVisibility(View.VISIBLE);
        } else {
            pubTime.setVisibility(View.GONE);
        }

        // 状态数
        if (bangumi.info.stat != null) {
            StringBuilder statBuilder = new StringBuilder();
            if (bangumi.info.stat.views > 0) {
                statBuilder.append("播放：").append(formatNumber(bangumi.info.stat.views));
            }
            if (bangumi.info.stat.favorites > 0) {
                if (statBuilder.length() > 0) statBuilder.append(" ");
                statBuilder.append("收藏：").append(formatNumber(bangumi.info.stat.favorites));
            }
            if (bangumi.info.stat.series_follow > 0) {
                if (statBuilder.length() > 0) statBuilder.append(" ");
                statBuilder.append("追番：").append(formatNumber(bangumi.info.stat.series_follow));
            }
            if (statBuilder.length() > 0) {
                stats.setText(statBuilder.toString());
                stats.setVisibility(View.VISIBLE);
            } else {
                stats.setVisibility(View.GONE);
            }
        } else {
            stats.setVisibility(View.GONE);
        }

        // 标签
        if (bangumi.info.styles != null && !bangumi.info.styles.isEmpty()) {
            String styleText = "标签：" + android.text.TextUtils.join(" ", bangumi.info.styles);
            styles.setText(styleText);
            styles.setVisibility(View.VISIBLE);
        } else {
            styles.setVisibility(View.GONE);
        }

        // 简介
        if (bangumi.info.evaluate != null && !bangumi.info.evaluate.trim().isEmpty()) {
            evaluate.setText(bangumi.info.evaluate.trim());
            evaluateHeader.setVisibility(View.VISIBLE);
            evaluate.setVisibility(View.GONE); // 默认折叠
            evaluateHeader.setOnClickListener(v -> {
                boolean isExpanded = evaluate.getVisibility() == View.VISIBLE;
                evaluate.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
                evaluateArrow.animate().rotation(isExpanded ? 0 : 180).setDuration(200).start();
            });
        } else {
            evaluateHeader.setVisibility(View.GONE);
            evaluate.setVisibility(View.GONE);
        }

        // 制作人员
        if (bangumi.info.staff != null && !bangumi.info.staff.trim().isEmpty()) {
            staff.setText(bangumi.info.staff.trim());
            staffHeader.setVisibility(View.VISIBLE);
            staff.setVisibility(View.GONE); // 默认折叠
            staffHeader.setOnClickListener(v -> {
                boolean isExpanded = staff.getVisibility() == View.VISIBLE;
                staff.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
                staffArrow.animate().rotation(isExpanded ? 0 : 180).setDuration(200).start();
            });
        } else {
            staffHeader.setVisibility(View.GONE);
            staff.setVisibility(View.GONE);
        }

        // 备案号
        if (bangumi.info.record != null && !bangumi.info.record.trim().isEmpty()) {
            record.setText("备案号：" + bangumi.info.record.trim());
            record.setVisibility(View.VISIBLE);
        } else {
            record.setVisibility(View.GONE);
        }
        //选集区设置：标题行 + 季 tab 行 + 横向集卡片
        EpisodeCardAdapter adapter = new EpisodeCardAdapter();
        episodeCardAdapter = adapter;

        adapter.setOnItemClickListener(index -> {
            selectedEpisode = index;
            refreshReplies();
        });

        TextView indexShow = rootView.findViewById(R.id.indexShow);
        indexShow.setText(bangumi.info.indexShow);

        if (bangumi.sectionList.isEmpty()) {
            episodeStatus.setText("敬请期待");
            seasonTabsRecyclerView.setVisibility(View.GONE);
            playButton.setVisibility(View.GONE);
            rootView.findViewById(R.id.episodes).setVisibility(View.GONE);    //未上线的番剧Activity activity = getActivity();
            Activity activity = getActivity();
            if (activity instanceof VideoInfoActivity) {
                ((VideoInfoActivity) activity).replyFragment.setRefreshing(false);
            }
            return;
        }

        //初始季入缓存：切走再切回时无需重新请求
        currentSeasonId = bangumi.info.season_id;
        BangumiApi.SeasonMeta initialMeta = new BangumiApi.SeasonMeta();
        initialMeta.sectionList = bangumi.sectionList;
        initialMeta.seasonType = bangumi.info != null ? bangumi.info.type : 0;
        initialMeta.statusDesc = bangumi.info != null ? bangumi.info.newEpDesc : null;
        seasonCache.put(currentSeasonId, initialMeta);

        setupSeasonTabs();
        updateEpisodeStatusText();

        episodeStatus.setOnClickListener(v -> {
            Dialog dialog = getEpisodeChooseDialog();
            if (dialog != null) dialog.show();
        });

        adapter.setData(bangumi.sectionList.get(0).episodeList);
        episodeRecyclerView.setLayoutManager(new CustomLinearManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        episodeRecyclerView.setAdapter(adapter);

        //play button setting
        playButton.setOnClickListener(v -> {
            Bangumi.Episode episode = getCurrentEpisode();
            if (episode != null) playEpisode(episode);
        });
        playButton.setOnLongClickListener(v -> {
            Intent intent = new Intent(v.getContext(), SettingPlayerChooseActivity.class);
            startActivity(intent);
            return true;
        });

        onFinishLoad();

        refreshReplies();

        //先定位再上报：reportEpisodeHistory 依赖定位后的选中集。
        //若在定位前上报初始集(第1集)，本季服务端观看记录会被"第1集 progress=0"覆盖，
        //后续定位（含官方客户端写入的记录）就只能命中这条 0 进度条目——自动定位失效的根因
        if (!locateLastWatchedEpisode()) reportEpisodeHistory();
    }

    /**
     * 上报当前选中集的历史，作用与普通视频详情页(VideoInfoFragment)的历史上报一致：
     * 让番剧出现在历史记录里并带上服务端记录的上次进度。
     * 番剧取流接口(pgc/player/web/playurl)不返回 last_play_*，所以进度单独查 x/player/wbi/v2。
     *
     * 触发时机：定位完成之后（上报的集 = 用户真正看过的集）；
     * 定位流程未启动时（未登录/开关关闭/数据异常）由 initView 直接调用。
     * 查到的进度为 0 时 reportHistoryPgc 会拒绝发送——避免把本季观看记录覆盖成 0 进度。
     */
    private void reportEpisodeHistory() {
        Bangumi.Section section = bangumi.sectionList.get(selectedSection);
        if (section == null || section.episodeList == null || section.episodeList.isEmpty()) return;
        Bangumi.Episode episode = section.episodeList.get(selectedEpisode);
        if (episode == null || episode.aid == 0 || episode.cid == 0) return;
        //season 维度必须在主线程快照：后台执行时 currentSeasonId 可能已被切季改变，
        //会把这一集上报到别的季的 season_id/sub_type 上
        final long fSeasonId = currentSeasonId;
        final int fSeasonType = currentSeasonType();
        CenterThreadPool.run(() -> {
            try {
                long progress = PlayerApi.getLastPlayProgress(episode.aid, episode.cid);
                //番剧必须走带 epid/sid 的心跳接口，用 history/report 不会被记成番剧记录
                HistoryApi.reportHistoryPgc(episode.aid, episode.cid, episode.id,
                        fSeasonId,
                        fSeasonType,
                        progress / 1000);
            } catch (Exception e) {
                Logu.e("BangumiInfoFragment", "历史上报失败: " + e.getMessage());
            }
        });
    }

    /**
     * 进入番剧详情页时，把选中集自动定位到"观看记录里最近看过的那一集"，
     * 使播放按钮与评论区默认落在用户上次看到的位置，而不是永远从第 1 集开始。
     *
     * 数据源走观看记录(HistoryApi)而不是逐集查进度：一季几十集逐集探测要发几十个请求。
     * 定位与"从历史位置播放"(player_from_last)开关保持一致，关掉该开关即不做定位。
     *
     * @return true 表示定位流程已启动，定位完成后会在回调内上报观看历史；
     *         false 表示流程未启动（未登录/开关关闭/数据异常），调用方需自行完成历史上报
     */
    private boolean locateLastWatchedEpisode() {
        if (bangumi == null || bangumi.sectionList == null || bangumi.sectionList.isEmpty()) return false;
        //未登录时没有观看记录，直接跳过
        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0) return false;
        if (!SharedPreferencesUtil.getBoolean("player_from_last", true)) return false;

        //先把 epid 映射到(分区下标, 集下标)，网络返回后直接查表，不用再遍历结构
        HashMap<Long, int[]> positionOfEpid = new HashMap<>();
        ArrayList<Long> epIds = new ArrayList<>();
        for (int s = 0; s < bangumi.sectionList.size(); s++) {
            Bangumi.Section section = bangumi.sectionList.get(s);
            if (section == null || section.episodeList == null) continue;
            for (int e = 0; e < section.episodeList.size(); e++) {
                long epid = section.episodeList.get(e).id;
                //epid 为 0 表示该字段缺失；重复 epid 只保留首次出现，避免定位到重复条目
                if (epid == 0 || positionOfEpid.containsKey(epid)) continue;
                positionOfEpid.put(epid, new int[]{s, e});
                epIds.add(epid);
            }
        }
        if (epIds.isEmpty()) return false;

        final long seasonAtStart = currentSeasonId;   //定位期间用户切季的话，结果按旧季下标套新季数据会错位
        CenterThreadPool.run(() -> {
            long epid = HistoryApi.findLastWatchedEpid(epIds);
            if (epid == 0) return; //从未看过本季：不上报，避免产生"progress=0"的污染记录
            int[] position = positionOfEpid.get(epid);
            if (position == null) return;
            CenterThreadPool.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (currentSeasonId != seasonAtStart) return;   //已经切到别的季：本轮定位作废
                //定位到第 1 集第 1 个时视觉上没有变化，就不弹提示，避免每次进详情页都打扰
                boolean moved = position[0] != 0 || position[1] != 0;
                Bangumi.Episode located = selectEpisode(position[0], position[1], true);
                if (moved && located != null) MsgUtil.showMsg("已定位到上次观看的 " + located.title);
                //定位完成后再上报当前（即用户真正看过的）集，写路径晚于读路径，竞态与污染一并消除
                reportEpisodeHistory();
            });
        });
        return true;
    }

    /**
     * 切换当前选中集，并同步 列表数据 / 高亮 / 滚动 / 评论区。
     * 与"点选集列表"相比，这里支持跨分区跳转，供自动定位使用。
     *
     * @return 实际选中的剧集；参数非法时返回 null
     */
    private Bangumi.Episode selectEpisode(int sectionIndex, int episodeIndex, boolean scrollToIt) {
        if (bangumi == null || bangumi.sectionList == null) return null;
        if (sectionIndex < 0 || sectionIndex >= bangumi.sectionList.size()) return null;
        Bangumi.Section section = bangumi.sectionList.get(sectionIndex);
        if (section == null || section.episodeList == null) return null;
        if (episodeIndex < 0 || episodeIndex >= section.episodeList.size()) return null;

        boolean sectionChanged = sectionIndex != selectedSection;
        selectedSection = sectionIndex;
        selectedEpisode = episodeIndex;

        EpisodeCardAdapter adapter = episodeCardAdapter;
        if (adapter != null) {
            //setData() 会把选中下标重置为 0，所以必须先 setData 再 setSelectedItemIndex
            if (sectionChanged) adapter.setData(section.episodeList);
            adapter.setSelectedItemIndex(episodeIndex);
            if (scrollToIt) episodeRecyclerView.scrollToPosition(episodeIndex);
        }

        refreshReplies();

        return section.episodeList.get(episodeIndex);
    }

    @SuppressLint("SetTextI18n")
    /**
     * 全量选集弹窗（标题行右侧"已完结，全N话 >"点击呼出）：
     * 扁平列出当前季全部分区的剧集（附加分区如"预告花絮"带前缀），选中后经 selectEpisode 跨分区跳转。
     */
    private Dialog getEpisodeChooseDialog() {
        ArrayList<String> choices = new ArrayList<>();
        ArrayList<int[]> positions = new ArrayList<>();
        boolean multipleSections = bangumi.sectionList.size() > 1;
        for (int s = 0; s < bangumi.sectionList.size(); s++) {
            Bangumi.Section section = bangumi.sectionList.get(s);
            if (section == null || section.episodeList == null) continue;
            for (int e = 0; e < section.episodeList.size(); e++) {
                Bangumi.Episode episode = section.episodeList.get(e);
                String name = (multipleSections ? "【" + section.title + "】" : "")
                        + episode.title
                        + (episode.title_long != null && !episode.title_long.isEmpty() ? " " + episode.title_long : "");
                choices.add(name);
                positions.add(new int[]{s, e});
            }
        }
        if (choices.isEmpty()) return null;

        int currentFlatIndex = -1;
        for (int i = 0; i < positions.size(); i++) {
            int[] p = positions.get(i);
            if (p[0] == selectedSection && p[1] == selectedEpisode) {
                currentFlatIndex = i;
                break;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setSingleChoiceItems(choices.toArray(new String[0]), Math.max(currentFlatIndex, 0), (dialog, which) -> {
            if (which < 0 || which >= positions.size()) return;
            int[] position = positions.get(which);
            selectEpisode(position[0], position[1], true);
            dialog.dismiss();
        });
        this.dialog = builder.create();

        return this.dialog;
    }

    // ------------------------ 季 tab（官方式页内切季） ------------------------

    /**
     * 初始化季 tab：数据源为 info.seasons（同系列所有季，与官方 tab 同源）。
     * 个别番剧 seasons 里不含当前季，此时把当前季补成第一个 tab（标题"正片"）。
     * 只有 tab 数 > 1 才显示整行（对齐 PiliPlus EpisodePanel 的 _isMulti 规则）。
     */
    private void setupSeasonTabs() {
        ArrayList<Bangumi.Season> tabs = new ArrayList<>();
        if (bangumi.info != null && bangumi.info.seasons != null) tabs.addAll(bangumi.info.seasons);

        int currentIndex = -1;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).season_id == currentSeasonId) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex < 0) {
            Bangumi.Season current = new Bangumi.Season();
            current.season_id = currentSeasonId;
            current.season_title = "正片";
            current.seasonType = bangumi.info != null ? bangumi.info.type : 0;
            current.statusDesc = bangumi.info != null ? bangumi.info.newEpDesc : null;
            tabs.add(0, current);
            currentIndex = 0;
        }
        //初始季的元数据回填（后续切回来时使用）
        Bangumi.Season initial = tabs.get(currentIndex);
        if (initial.seasonType == 0 && bangumi.info != null) initial.seasonType = bangumi.info.type;
        if ((initial.statusDesc == null || initial.statusDesc.isEmpty()) && bangumi.info != null)
            initial.statusDesc = bangumi.info.newEpDesc;

        if (tabs.size() <= 1) {
            seasonTabsRecyclerView.setVisibility(View.GONE);
            return;
        }

        seasonTabAdapter = new SeasonTabAdapter(tabs);
        seasonTabAdapter.selectedIndex = currentIndex;
        seasonTabAdapter.setOnTabClickListener(this::switchSeason);
        seasonTabsRecyclerView.setLayoutManager(new CustomLinearManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        seasonTabsRecyclerView.setAdapter(seasonTabAdapter);
        seasonTabsRecyclerView.setVisibility(View.VISIBLE);
    }

    /**
     * 切季：缓存命中直接应用，否则后台拉取（getSeasonDetail 一次带回集列表/季类型/状态文案/上次看到 epid）。
     * 请求期间忽略重复点击；失败提示并停留当前季。
     */
    private void switchSeason(int tabIndex) {
        if (seasonTabAdapter == null || switchingSeason) return;
        if (tabIndex < 0 || tabIndex >= seasonTabAdapter.getItemCount()) return;
        Bangumi.Season target = seasonTabAdapter.getItem(tabIndex);
        if (target == null || target.season_id == currentSeasonId) return;

        BangumiApi.SeasonMeta cached = seasonCache.get(target.season_id);
        if (cached != null && !cached.sectionList.isEmpty()) {
            applySeason(tabIndex, target, cached);
            return;
        }

        switchingSeason = true;
        CenterThreadPool.run(() -> {
            try {
                BangumiApi.SeasonMeta meta = BangumiApi.getSeasonDetail(target.season_id);
                //必须确保目标季"至少有一个非空分区"：只有空附加分区（或空 episodes）的季不能进 applySeason，
                //否则会留下 tab 高亮/列表/选中下标半更新的状态，用户再点集卡片就会越界崩溃
                boolean hasEpisodes = false;
                for (Bangumi.Section section : meta.sectionList) {
                    if (section.episodeList != null && !section.episodeList.isEmpty()) {
                        hasEpisodes = true;
                        break;
                    }
                }
                if (!hasEpisodes) {
                    CenterThreadPool.runOnUiThread(() -> {
                        if (isAdded()) MsgUtil.showMsg("该季暂无内容");
                        switchingSeason = false;
                    });
                    return;
                }
                seasonCache.put(target.season_id, meta);
                CenterThreadPool.runOnUiThread(() -> {
                    switchingSeason = false;
                    if (!isAdded()) return;
                    applySeason(tabIndex, target, meta);
                });
            } catch (Exception e) {
                Logu.e("BangumiInfoFragment", "切季失败: " + e.getMessage());
                CenterThreadPool.runOnUiThread(() -> {
                    switchingSeason = false;
                    if (isAdded()) MsgUtil.showMsg("切换失败，请稍后重试");
                });
            }
        });
    }

    /**
     * 应用切季结果（主线程）：记忆/恢复各季选中位置，交换分区列表，
     * 命中"上次看到"（user/status 的 progress）时优先定位并提示。
     */
    private void applySeason(int tabIndex, Bangumi.Season tab, BangumiApi.SeasonMeta meta) {
        //记住离开的这一季的选中位置
        seasonSelection.put(currentSeasonId, new int[]{selectedSection, selectedEpisode});

        long previousSeasonId = currentSeasonId;
        currentSeasonId = tab.season_id;
        tab.seasonType = meta.seasonType != 0 ? meta.seasonType : (bangumi.info != null ? bangumi.info.type : 0);
        tab.statusDesc = meta.statusDesc;

        bangumi.sectionList = meta.sectionList;

        //恢复目标季的选中位置：上次看到 epid 优先，其次上次离开时的记忆，最后第1集
        int[] selection = seasonSelection.containsKey(currentSeasonId)
                ? seasonSelection.get(currentSeasonId) : new int[]{0, 0};
        boolean locatedByProgress = false;
        if (meta.lastEpid > 0) {
            int[] found = findEpisodePositionByEpid(meta.lastEpid);
            if (found != null) {
                selection = found;
                locatedByProgress = true;
            }
        }
        //越界兜底（服务端数据可能变化）
        int sectionIdx = Math.min(Math.max(selection[0], 0), bangumi.sectionList.size() - 1);
        Bangumi.Section section = bangumi.sectionList.get(sectionIdx);
        int episodeIdx = selection[1];
        if (section.episodeList == null || section.episodeList.isEmpty()) {
            //记忆的分区已空：回落到正片（getSeasonDetail 保证 index 0 是非空正片）
            sectionIdx = 0;
            episodeIdx = 0;
            section = bangumi.sectionList.get(0);
            if (section.episodeList == null || section.episodeList.isEmpty()) return;
        }
        episodeIdx = Math.min(Math.max(episodeIdx, 0), section.episodeList.size() - 1);

        selectedSection = sectionIdx;
        selectedEpisode = episodeIdx;

        seasonTabAdapter.setSelectedIndex(tabIndex);
        updateEpisodeStatusText();

        episodeCardAdapter.setData(section.episodeList);
        episodeCardAdapter.setSelectedItemIndex(episodeIdx);
        episodeRecyclerView.scrollToPosition(episodeIdx);

        refreshReplies();

        Bangumi.Episode located = section.episodeList.get(episodeIdx);
        if (locatedByProgress && !(sectionIdx == 0 && episodeIdx == 0)) {
            MsgUtil.showMsg("已定位到上次观看的 " + located.title);
        }
        //与进入详情页同语义：切到某季的某集后上报该集（reportHistoryPgc 内部会拒绝 0 进度，不会产生污染记录）
        if (previousSeasonId != currentSeasonId) reportEpisodeHistory();
    }

    /**在该季分区列表里按 epid 查 (sectionIdx, epIdx)，查不到返回 null。*/
    private int[] findEpisodePositionByEpid(long epid) {
        if (epid <= 0 || bangumi.sectionList == null) return null;
        for (int s = 0; s < bangumi.sectionList.size(); s++) {
            Bangumi.Section section = bangumi.sectionList.get(s);
            if (section == null || section.episodeList == null) continue;
            for (int e = 0; e < section.episodeList.size(); e++) {
                Bangumi.Episode episode = section.episodeList.get(e);
                if (episode != null && episode.id == epid) return new int[]{s, e};
            }
        }
        return null;
    }

    // ------------------------ 播放与状态文案 ------------------------

    private Bangumi.Episode getCurrentEpisode() {
        if (bangumi == null || bangumi.sectionList == null
                || selectedSection < 0 || selectedSection >= bangumi.sectionList.size()) return null;
        Bangumi.Section section = bangumi.sectionList.get(selectedSection);
        if (section == null || section.episodeList == null
                || selectedEpisode < 0 || selectedEpisode >= section.episodeList.size()) return null;
        return section.episodeList.get(selectedEpisode);
    }

    /**
     * 当前季的类型（心跳上报 sub_type）：优先 tab 元数据（切季懒加载回填），回退 info.type。
     */
    private int currentSeasonType() {
        if (seasonTabAdapter != null) {
            Bangumi.Season tab = seasonTabAdapter.getItem(seasonTabAdapter.selectedIndex);
            if (tab != null && tab.seasonType != 0) return tab.seasonType;
        }
        return bangumi.info != null ? bangumi.info.type : 0;
    }

    private void playEpisode(Bangumi.Episode episode) {
        Glide.get(requireContext()).clearMemory();
        Intent intent = new Intent(requireContext(), JumpToPlayerActivity.class);
        PlayerData data = episode.toPlayerData();
        //epid 已由 Episode.toPlayerData() 带上；season 维度必须用"当前展示的季"（页内切季后与 info.season_id 不同），
        //番剧进度上报(x/click-interface/web/heartbeat)需要 sid/sub_type
        data.seasonId = currentSeasonId;
        data.seasonType = currentSeasonType();
        intent.putExtra("data", data);
        startActivity(intent);
    }

    /**
     * 标题行右侧的状态文案：优先该季的 new_ep.desc（"已完结, 全N话"，与官方同源），
     * 缺失时用完结状态 + 正片集数拼接。
     */
    @SuppressLint("SetTextI18n")
    private void updateEpisodeStatusText() {
        if (episodeStatus == null || bangumi == null || bangumi.info == null) return;
        String desc = null;
        if (seasonTabAdapter != null) {
            Bangumi.Season tab = seasonTabAdapter.getItem(seasonTabAdapter.selectedIndex);
            if (tab != null && tab.statusDesc != null && !tab.statusDesc.isEmpty()) desc = tab.statusDesc;
        }
        if (desc == null && currentSeasonId == bangumi.info.season_id
                && bangumi.info.newEpDesc != null && !bangumi.info.newEpDesc.isEmpty())
            desc = bangumi.info.newEpDesc;

        if ((desc == null || desc.isEmpty()) && bangumi.sectionList != null && !bangumi.sectionList.isEmpty()
                && bangumi.sectionList.get(0).episodeList != null) {
            int count = bangumi.sectionList.get(0).episodeList.size();
            boolean finished = bangumi.info.publish != null && bangumi.info.publish.is_finish == 1;
            desc = (finished ? "已完结" : "连载中") + "，共" + count + "话";
        }
        if (desc == null) desc = "选集";
        episodeStatus.setText(desc);
    }

    private void refreshReplies() {
        Activity activity = getActivity();
        if (activity instanceof VideoInfoActivity) {
            //级联 get 无任何越界保护：分区/剧集列表为空或下标越界（切季懒加载未回填时）
            //直接 IndexOutOfBounds，改用带完整判空与越界检查的 getCurrentEpisode()
            Bangumi.Episode episode = getCurrentEpisode();
            if (episode != null) ((VideoInfoActivity) activity).setCurrentAid(episode.aid);
        }
    }

    public void onFinishLoad() {
        try {
            Activity activity = requireActivity();
            if (activity instanceof VideoInfoActivity) {
                ((VideoInfoActivity) activity).crossFade(getView());
            }
        } catch (Exception ignored) {
        }
    }

    private String formatNumber(int num) {
        if (num >= 100000000) { // 亿
            return String.format("%.1f亿", num / 100000000.0);
        } else if (num >= 10000) { // 万
            return String.format("%.1f万", num / 10000.0);
        } else {
            return String.valueOf(num);
        }
    }

    /**查询追番状态：参照 PiliPlus 用 /pgc/view/web/season/user/status */
    private void checkFollowStatus() {
        if (bangumi == null || bangumi.info == null) return;
        long sid = bangumi.info.season_id;
        if (SharedPreferencesUtil.getBoolean("bangumi_follow_" + sid, false)) {
            Button btn = rootView.findViewById(R.id.btn_follow);
            btn.setText("已追番");
            return;
        }
        Button btn = rootView.findViewById(R.id.btn_follow);
        CenterThreadPool.run(() -> {
            try {
                String url = "https://api.bilibili.com/pgc/view/web/season/user/status?season_id=" + sid;
                org.json.JSONObject root = NetWorkUtil.getJson(url);
                if (root.optInt("code") == 0) {
                    org.json.JSONObject result = root.optJSONObject("result");
                    if (result != null && result.optInt("follow", 0) == 1) {
                        SharedPreferencesUtil.putBoolean("bangumi_follow_" + sid, true);
                        //不能用 requireActivity().runOnUiThread：fragment 可能已 detach，会抛异常
                        CenterThreadPool.runOnUiThread(() -> btn.setText("已追番"));
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void toggleFollow(Button btn) {
        if (bangumi == null || bangumi.info == null) return;
        long sid = bangumi.info.season_id;
        String cacheKey = "bangumi_follow_" + sid;
        boolean isFollowing = SharedPreferencesUtil.getBoolean(cacheKey, false);
        CenterThreadPool.run(() -> {
            try {
                String url = "https://api.bilibili.com/pgc/web/follow/" + (isFollowing ? "del" : "add");
                String body = "season_id=" + sid + "&csrf=" + SharedPreferencesUtil.getString("csrf", "");
                NetWorkUtil.post(url, body, NetWorkUtil.webHeaders).body().string();
                SharedPreferencesUtil.putBoolean(cacheKey, !isFollowing);
                //回调统一走 Handler 投递：请求完成时 fragment 可能已 detach，
                //requireActivity() 在 try 与 catch 里抛异常都会直接把进程打死
                CenterThreadPool.runOnUiThread(() -> {
                    btn.setText(isFollowing ? "追番" : "已追番");
                    MsgUtil.showMsg(isFollowing ? "已取消追番" : "已追番");
                });
            } catch (Exception e) {
                CenterThreadPool.runOnUiThread(() -> MsgUtil.showMsg("操作失败，请稍后重试"));
            }
        });
    }

}
