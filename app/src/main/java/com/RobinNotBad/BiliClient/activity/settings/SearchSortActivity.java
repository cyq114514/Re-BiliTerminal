package com.RobinNotBad.BiliClient.activity.settings;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.adapter.DragAdapter;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 搜索类别排序页：拖拽调整搜索页各分类（视频/番剧/专栏/用户/直播）在 ViewPager 里的左右顺序。
 *
 * <p>与「菜单排序」（{@link SortSettingActivity}）同一套路，差异有三：
 * ① 视频钉死在第一位（长按入口与拖拽回调双重拦截 0 位）；
 * ② 禁用滑动删除——分类是固定集合，删掉一个少一个；
 * ③ 解析入口收敛到 {@link #loadOrderedKeys()}，搜索页（SearchActivity）按它的顺序建 tab，
 *    两边共用一份口径（菜单排序是 MenuActivity 与 SortSettingActivity 各写一遍解析，容易走偏）。
 *
 * <p>存储格式与 MENU_SORT 相同：分号分隔的类别键序（如 video;bangumi;article;user;live）。
 * 容忍式解析：保存的每一项都必须是已知类别，默认表里新增、老配置里还没有的类别按默认顺序
 * 补在末尾——以后加新分类不会把老用户排好的顺序整份作废（MenuActivity.parseSortConf 同教训）。
 */
public class SearchSortActivity extends BaseActivity {

    /** 视频钉在首位：进搜索页先看视频结果是主场景。 */
    public static final int FIXED_POSITION = 0;

    public static final String[] DEFAULT_ORDER = {"video", "bangumi", "article", "user", "live"};

    /** 类别键 → 显示名。用 LinkedHashMap 固定默认顺序，新增类别往尾部加。 */
    public static final Map<String, String> CATEGORY_NAMES = new LinkedHashMap<String, String>() {
        {
            put("video", "视频");
            put("bangumi", "番剧");
            put("article", "专栏");
            put("user", "用户");
            put("live", "直播");
        }
    };

    final List<String> data = new ArrayList<>();
    final Map<String, String> displayKeyMap = new HashMap<>();

    /** 原始配置串（未保存过 = 空串）。SearchActivity.onResume 靠对比它决定要不要按新顺序重建。 */
    public static String getSortConf() {
        return SharedPreferencesUtil.getString(SharedPreferencesUtil.SEARCH_CATEGORY_SORT, "");
    }

    /**
     * 按用户保存的顺序给出类别键列表；视频永远在第一位。
     * SearchActivity 用它构建 fragmentList——这是排序的唯一解析入口，改格式只改这里。
     */
    public static List<String> loadOrderedKeys() {
        List<String> keys = new ArrayList<>();
        keys.add("video"); //钉死首位
        String sortConf = getSortConf();
        if (!TextUtils.isEmpty(sortConf)) {
            for (String key : sortConf.split(";")) {
                if (key.equals("video") || !CATEGORY_NAMES.containsKey(key)) continue;
                if (!keys.contains(key)) keys.add(key);
            }
        }
        //本次新增、老配置里还没有的类别按默认顺序补在末尾
        for (String key : DEFAULT_ORDER) {
            if (!keys.contains(key)) keys.add(key);
        }
        return keys;
    }

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_list);

        setPageName("搜索类别排序");

        rebuildData();

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        DragAdapter dragAdapter = new DragAdapter(this, data);
        recyclerView.setAdapter(dragAdapter);

        //仅拖拽排序、不支持滑动删除、0 位（视频）不可动的回调
        CategoryDragCallBack dragCallBack = new CategoryDragCallBack(dragAdapter, data);
        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(dragCallBack);
        itemTouchHelper.attachToRecyclerView(recyclerView);

        dragAdapter.setOnItemClickListener(new DragAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(int position) {
            }

            @Override
            public void onItemLongClick(DragAdapter.ViewHolder holder) {
                //视频固定在首位，不允许拖动。ItemTouchHelper 自身的长按检测已关
                //（见 CategoryDragCallBack#isLongPressDragEnabled），这里不拦就会绕过固定位
                if (holder.getAdapterPosition() != FIXED_POSITION) {
                    itemTouchHelper.startDrag(holder);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        MsgUtil.showMsg("长按拖动以排序~");
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
        MsgUtil.showMsg("已保存");
    }

    private void rebuildData() {
        data.clear();
        displayKeyMap.clear();
        for (String key : loadOrderedKeys()) {
            String displayText = Objects.requireNonNull(CATEGORY_NAMES.get(key));
            data.add(displayText);
            displayKeyMap.put(displayText, key);
        }
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        boolean flag = false;
        for (String s : data) {
            if (displayKeyMap.containsKey(s)) {
                if (flag) {
                    sb.append(";");
                } else {
                    flag = true;
                }
                sb.append(displayKeyMap.get(s));
            }
        }
        SharedPreferencesUtil.putString(SharedPreferencesUtil.SEARCH_CATEGORY_SORT, sb.toString());
    }

    /**
     * 搜索类别排序专用拖拽回调。
     *
     * <p>与 SortSettingActivity.DragCallBack 的两个关键差异：
     * ① 0 位（视频）不可动，onMove 对 from/to 任一为 0 的移动直接拒绝；
     * ② {@link #isLongPressDragEnabled()} 返回 false——ItemTouchHelper 自带的长按检测不知道
     * 固定位概念，放开会绕过 onItemLongClick 里的 0 位拦截（菜单排序没这个问题，
     * 因为它的 DragAdapter.getFixedPosition 恒为 -1，拖谁都不拦）。
     */
    static class CategoryDragCallBack extends ItemTouchHelper.Callback {

        final DragAdapter mAdapter;
        final List<String> mData;

        CategoryDragCallBack(DragAdapter adapter, List<String> data) {
            this.mAdapter = adapter;
            this.mData = data;
        }

        @Override
        public int getMovementFlags(RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            //仅支持上下拖动，不支持滑动删除
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView,
                              RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
            int fromPosition = viewHolder.getAdapterPosition();
            int toPosition = target.getAdapterPosition();
            //视频固定在首位：位置 0 的项不可被拖走，别的项也不可被拖到位置 0
            if (fromPosition == FIXED_POSITION || toPosition == FIXED_POSITION) {
                return false;
            }

            if (fromPosition < toPosition) {
                for (int index = fromPosition; index < toPosition; index++) {
                    Collections.swap(mData, index, index + 1);
                }
            } else {
                for (int index = fromPosition; index > toPosition; index--) {
                    Collections.swap(mData, index, index - 1);
                }
            }
            mAdapter.notifyItemMoved(fromPosition, toPosition);
            return true;
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
            //不支持滑动删除，分类是固定集合
        }

        @Override
        public void onSelectedChanged(@Nullable RecyclerView.ViewHolder viewHolder, int actionState) {
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE && viewHolder != null) {
                ViewCompat.animate(viewHolder.itemView).setDuration(200).scaleX(1.3F).scaleY(1.3F).start();
            }
            super.onSelectedChanged(viewHolder, actionState);
        }

        @Override
        public void clearView(RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            ViewCompat.animate(viewHolder.itemView).setDuration(200).scaleX(1F).scaleY(1F).start();
            super.clearView(recyclerView, viewHolder);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false; //长按启动走 onItemLongClick 的固定位拦截，见类注释
        }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return false;
        }
    }
}
