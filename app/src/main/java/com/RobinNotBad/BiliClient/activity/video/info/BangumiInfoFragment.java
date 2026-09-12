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
import com.RobinNotBad.BiliClient.adapter.video.MediaEpisodeAdapter;
import com.RobinNotBad.BiliClient.api.BangumiApi;
import com.RobinNotBad.BiliClient.api.HistoryApi;
import com.RobinNotBad.BiliClient.api.PlayerApi;
import com.RobinNotBad.BiliClient.model.Bangumi;
import com.RobinNotBad.BiliClient.model.PlayerData;
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager;
import androidx.lifecycle.MutableLiveData;

import com.RobinNotBad.BiliClient.util.CenterThreadPool;
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
import java.util.Objects;

public class BangumiInfoFragment extends Fragment {
    private long mediaId;
    private int selectedSection = 0, selectedEpisode = 0;
    private Dialog dialog;
    private View rootView;
    private RecyclerView episodeRecyclerView;
    private Button section_choose;
    private TextView episode_choose;
    private Bangumi bangumi;

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
        section_choose = rootView.findViewById(R.id.section_choose);
        episode_choose = rootView.findViewById(R.id.episode_choose);
        selectedSection = 0;

        rootView.setVisibility(View.GONE);

        Glide.with(requireContext())
                .load(GlideUtil.url(bangumi.info.cover_horizontal))
                .transition(GlideUtil.getTransitionOptions())
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .placeholder(R.mipmap.placeholder)
                .into(imageMediaCover);
        imageMediaCover.setOnClickListener((view) -> startActivity(new Intent(view.getContext(), ImageViewerActivity.class).putExtra("imageList", new ArrayList<>(List.of(bangumi.info.cover_horizontal)))));
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
            String styleText = "标签：" + String.join(" ", bangumi.info.styles);
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
        //section selector setting.
        MediaEpisodeAdapter adapter = new MediaEpisodeAdapter();

        adapter.setOnItemClickListener(index -> {
            selectedEpisode = index;
            refreshReplies();
        });

        TextView indexShow = rootView.findViewById(R.id.indexShow);
        indexShow.setText(bangumi.info.indexShow);

        if (bangumi.sectionList.isEmpty()) {
            section_choose.setText("敬请期待");
            playButton.setVisibility(View.GONE);
            rootView.findViewById(R.id.episodes).setVisibility(View.GONE);    //未上线的番剧Activity activity = getActivity();
            Activity activity = requireActivity();
            if (activity instanceof VideoInfoActivity) {
                ((VideoInfoActivity) activity).replyFragment.setRefreshing(false);
            }
            return;
        }

        section_choose.setText(bangumi.sectionList.get(0).title + " 点击切换");
        section_choose.setOnClickListener(v -> getSectionChooseDialog().show());
        episode_choose.setOnClickListener(v -> getEposideChooseDialog().show());

        adapter.setData(bangumi.sectionList.get(0).episodeList);
        episodeRecyclerView.setLayoutManager(new CustomLinearManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        episodeRecyclerView.setAdapter(adapter);

        //play button setting
        playButton.setOnClickListener(v -> {
            Bangumi.Episode episode = bangumi.sectionList.get(selectedSection).episodeList.get(selectedEpisode);
            Glide.get(requireContext()).clearMemory();
            Intent intent = new Intent(v.getContext(), JumpToPlayerActivity.class);
            PlayerData data = episode.toPlayerData();
            //epid 已由 Episode.toPlayerData() 带上；season 维度只有详情页拿得到 Info，在这里补齐，
            //番剧进度上报(x/click-interface/web/heartbeat)需要 sid/sub_type
            if (bangumi.info != null) {
                data.seasonId = bangumi.info.season_id;
                data.seasonType = bangumi.info.type;
            }
            intent.putExtra("data", data);
            startActivity(intent);
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
        CenterThreadPool.run(() -> {
            try {
                long progress = PlayerApi.getLastPlayProgress(episode.aid, episode.cid);
                //番剧必须走带 epid/sid 的心跳接口，用 history/report 不会被记成番剧记录
                HistoryApi.reportHistoryPgc(episode.aid, episode.cid, episode.id,
                        bangumi.info != null ? bangumi.info.season_id : 0,
                        bangumi.info != null ? bangumi.info.type : 0,
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

        CenterThreadPool.run(() -> {
            long epid = HistoryApi.findLastWatchedEpid(epIds);
            if (epid == 0) return; //从未看过本季：不上报，避免产生"progress=0"的污染记录
            int[] position = positionOfEpid.get(epid);
            if (position == null) return;
            CenterThreadPool.runOnUiThread(() -> {
                if (!isAdded()) return;
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
     * 切换当前选中集，并同步 分区文案 / 列表数据 / 高亮 / 滚动 / 评论区。
     * 与"点选集列表""选集数弹窗"相比，这里支持跨分区跳转，供自动定位使用。
     *
     * @return 实际选中的剧集；参数非法时返回 null
     */
    @SuppressLint("SetTextI18n")
    private Bangumi.Episode selectEpisode(int sectionIndex, int episodeIndex, boolean scrollToIt) {
        if (bangumi == null || bangumi.sectionList == null) return null;
        if (sectionIndex < 0 || sectionIndex >= bangumi.sectionList.size()) return null;
        Bangumi.Section section = bangumi.sectionList.get(sectionIndex);
        if (section == null || section.episodeList == null) return null;
        if (episodeIndex < 0 || episodeIndex >= section.episodeList.size()) return null;

        boolean sectionChanged = sectionIndex != selectedSection;
        selectedSection = sectionIndex;
        selectedEpisode = episodeIndex;

        section_choose.setText(section.title + " 点击切换");

        MediaEpisodeAdapter adapter = episodeRecyclerView != null ? (MediaEpisodeAdapter) episodeRecyclerView.getAdapter() : null;
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
    private Dialog getSectionChooseDialog() {
        String[] choices = new String[bangumi.sectionList.size()];
        for (int i = 0; i < bangumi.sectionList.size(); i++) {
            choices[i] = bangumi.sectionList.get(i).title;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setSingleChoiceItems(choices, selectedSection, (dialog, which) -> {
            selectedSection = which;
            selectedEpisode = 0;

            refreshReplies();
            Bangumi.Section section = bangumi.sectionList.get(which);
            section_choose.setText(section.title + " 点击切换");
            MediaEpisodeAdapter adapter = (MediaEpisodeAdapter) episodeRecyclerView.getAdapter();
            if (adapter != null) {
                adapter.setData(bangumi.sectionList.get(which).episodeList);
                episodeRecyclerView.scrollToPosition(0);
            }
            episode_choose.setOnClickListener(v -> getEposideChooseDialog().show());
            dialog.dismiss();
        });
        dialog = builder.create();

        return dialog;
    }

    private Dialog getEposideChooseDialog() {
        ArrayList<Bangumi.Episode> episodeList = bangumi.sectionList.get(selectedSection).episodeList;

        String[] choices = new String[episodeList.size()];
        for (int i = 0; i < episodeList.size(); i++) {
            Bangumi.Episode episode = episodeList.get(i);
            choices[i] = episode.title + "." + episode.title_long;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setSingleChoiceItems(choices, selectedEpisode, (dialog, which) -> {
            selectedEpisode = which;
            refreshReplies();

            MediaEpisodeAdapter adapter = (MediaEpisodeAdapter) episodeRecyclerView.getAdapter();
            if (adapter != null) {
                adapter.setSelectedItemIndex(which);
                episodeRecyclerView.scrollToPosition(which);
            }
            dialog.dismiss();
        });
        dialog = builder.create();

        return dialog;
    }

    private void refreshReplies() {
        Activity activity = getActivity();
        if (activity instanceof VideoInfoActivity) {
            ((VideoInfoActivity) activity).setCurrentAid(bangumi.sectionList.get(selectedSection).episodeList.get(selectedEpisode).aid);
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
                        requireActivity().runOnUiThread(() -> btn.setText("已追番"));
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
                requireActivity().runOnUiThread(() -> {
                    btn.setText(isFollowing ? "追番" : "已追番");
                    MsgUtil.showMsg(isFollowing ? "已取消追番" : "已追番");
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> MsgUtil.showMsg("操作失败，请稍后重试"));
            }
        });
    }

}
