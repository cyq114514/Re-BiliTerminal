package com.RobinNotBad.BiliClient.adapter.video;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.listener.OnItemClickListener;
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener;
import com.RobinNotBad.BiliClient.model.DownloadSection;
import com.RobinNotBad.BiliClient.service.DownloadService;
import com.RobinNotBad.BiliClient.ui.theme.ThemeManager;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.google.android.material.card.MaterialCardView;
import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

//下载列表Adapter
//2024-11-24

public class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.DownloadHolder> {

    Context context;
    public ArrayList<DownloadSection> downloadList;
    OnItemLongClickListener longClickListener;
    OnItemClickListener clickListener;

    //------------------------------------------------------------ 批量删除（多选）
    //与收藏夹的「管理」模式同一套交互：进入后单击=勾选，长按删除被禁用，
    //选中的项用卡片高亮（强调色 10% 底 + 2dp 描边）表示，与 BiliCardView 的选中态一致。
    private boolean selectionMode = false;
    private final Set<Long> selectedIds = new HashSet<>();
    private OnSelectionChangedListener selectionChangedListener;

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int count);
    }

    public DownloadAdapter(Context context, ArrayList<DownloadSection> downloadList) {
        this.context = context;
        this.downloadList = downloadList;
    }

    public void setOnLongClickListener(OnItemLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setOnClickListener(OnItemClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.selectionChangedListener = listener;
    }

    /** 进入/退出多选：进入时清空已选，退出时恢复普通点击行为 */
    public void setSelectionMode(boolean on) {
        this.selectionMode = on;
        selectedIds.clear();
        notifyDataSetChanged();
        if (selectionChangedListener != null) selectionChangedListener.onSelectionChanged(0);
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public int getSelectedCount() {
        return selectedIds.size();
    }

    /** 已选中的下载记录 id（Activity 拿它去映射 DownloadSection 删文件+删记录） */
    public List<Long> getSelectedIds() {
        return new ArrayList<>(selectedIds);
    }

    @NonNull
    @Override
    public DownloadHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(this.context).inflate(R.layout.cell_video_local, parent, false);
        return new DownloadHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DownloadHolder holder, int position) {
        if (downloadList == null || downloadList.isEmpty()) {
            holder.show(null, context);
            holder.showProgress(null, -1);
            holder.setSelectedState(false, false);
            holder.itemView.setOnClickListener(null);
            holder.itemView.setOnLongClickListener(null);
            return;
        }

        DownloadSection section = downloadList.get(position);
        holder.show(section, context);

        if (section.state.equals("downloading") && DownloadService.section != null
                && DownloadService.section.id == section.id) {
            holder.showProgress(DownloadService.state, DownloadService.percent);
        } else {
            holder.showProgress(null, -1);
        }

        final long sectionId = section.id;
        holder.setSelectedState(selectionMode, selectedIds.contains(sectionId));

        holder.itemView.setOnClickListener(view -> {
            if (selectionMode) {
                if (selectedIds.contains(sectionId)) selectedIds.remove(sectionId);
                else selectedIds.add(sectionId);
                //只刷这一行：整表 notifyDataSetChanged 会让可见行全部重绑（低端手表上点一下明显顿挫）
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos);
                if (selectionChangedListener != null)
                    selectionChangedListener.onSelectionChanged(selectedIds.size());
                return;
            }
            if (clickListener != null) {
                clickListener.onItemClick(position);
            }
        });

        holder.itemView.setOnLongClickListener(view -> {
            //批量模式下禁用长按删除：此时单击就是勾选，长按误删的风险比收益大
            if (selectionMode) return true;
            if (longClickListener != null) {
                longClickListener.onItemLongClick(position);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return downloadList != null && !downloadList.isEmpty() ? downloadList.size() : 1;
    }

    public static class DownloadHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView extra;
        final ImageView cover;
        final View progress;

        public DownloadHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.text_title);
            cover = itemView.findViewById(R.id.img_cover);
            progress = itemView.findViewById(R.id.progress);
            extra = itemView.findViewById(R.id.text_extra);
        }

        public void show(DownloadSection section, Context context) {
            if (section == null) {
                title.setText("没有下载中的项");
                extra.setText("点击下面继续下载喵？");
                cover.setImageResource(R.mipmap.placeholder);
                return;
            }
            title.setText(section.name_short);
            switch (section.state) {
                case "error":
                    extra.setText("下载出错");
                    break;
                case "none":
                    extra.setText("等待下载");
                    break;
                case "downloading":
                    if (DownloadService.section == null)
                        extra.setText("下载中断");
                    break;
                default:
                    extra.setText("未知状态？");
            }

            if (!section.url_cover.isEmpty())
                //封面走 GlideUtil.url() 的服务端降采样（@25q_512w），避免直载原始大图（requestCover 内部已走 url()）
                GlideUtil.requestCover(cover, section.url_cover, R.mipmap.placeholder);
        }

        /**
         * 批量模式的选中态：与 BiliCardView 的选中态同一套（强调色 10% 底 + 2dp 强调色描边）。
         * 非选中/非批量模式恢复 CardStyle 的默认值（半透明灰底 + 0.5dp 发丝描边）。
         * 注意必须显式写回默认值：MaterialCardView 一旦被改过 stroke，不会再回落到 style。
         */
        void setSelectedState(boolean selectionMode, boolean selected) {
            if (!(itemView instanceof MaterialCardView)) return;
            MaterialCardView card = (MaterialCardView) itemView;
            Context ctx = itemView.getContext();
            float density = itemView.getResources().getDisplayMetrics().density;
            if (selectionMode && selected) {
                card.setCardBackgroundColor(ThemeManager.getPrimarySoft());
                card.setStrokeColor(ThemeManager.getPrimaryBorder());
                card.setStrokeWidth(Math.round(density * 2f));
            } else {
                card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_bg_new));
                card.setStrokeColor(ContextCompat.getColor(ctx, R.color.card_border_new));
                card.setStrokeWidth(Math.max(1, Math.round(density * 0.5f)));
            }
        }

        @SuppressLint({"SetTextI18n"})
        public void showProgress(String state, float percent) {
            if (state == null || percent == -1) {
                progress.setVisibility(View.GONE);
                return;
            }
            progress.setVisibility(View.VISIBLE);
            extra.setVisibility(View.VISIBLE);
            extra.setText(state + "：" + String.format(Locale.CHINA, "%.2f", percent * 100) + "%");
            int width = itemView.getMeasuredWidth();
            if (width > 0) {
                ViewGroup.LayoutParams layoutParams = progress.getLayoutParams();
                layoutParams.width = (int) (width * percent);
                progress.setLayoutParams(layoutParams);
            } else {
                progress.post(new Runnable() {
                    @Override
                    public void run() {
                        int measuredWidth = itemView.getMeasuredWidth();
                        if (measuredWidth > 0) {
                            ViewGroup.LayoutParams layoutParams = progress.getLayoutParams();
                            layoutParams.width = (int) (measuredWidth * percent);
                            progress.setLayoutParams(layoutParams);
                        }
                    }
                });
            }
        }
    }
}
