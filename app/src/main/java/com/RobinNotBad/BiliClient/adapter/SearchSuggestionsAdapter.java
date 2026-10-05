package com.RobinNotBad.BiliClient.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.listener.OnItemClickListener;
import com.RobinNotBad.BiliClient.util.StringUtil;

import java.util.ArrayList;
import java.util.Objects;

public class SearchSuggestionsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    //段标题行（如"热搜"）：热词与输入建议共用这张列表，靠标题区分内容来源
    private static final int TYPE_SECTION_TITLE = 1;

    final Context context;
    final ArrayList<String> suggestionsList;
    OnItemClickListener clickListener;
    private String sectionTitle;

    public SearchSuggestionsAdapter(Context context, ArrayList<String> suggestionsList) {
        this.context = context;
        this.suggestionsList = suggestionsList;
    }

    /**设置段标题：null/空 = 无标题（输入建议模式）；非空 = 列表首行插入不可点击的标题行。*/
    @SuppressLint("NotifyDataSetChanged")
    public void setSectionTitle(String title) {
        if (Objects.equals(sectionTitle, title)) return;
        boolean hadTitle = hasTitle();
        sectionTitle = title;
        if (hadTitle && hasTitle()) notifyItemChanged(0);
        else if (hadTitle) notifyItemRemoved(0);
        else if (hasTitle()) notifyItemInserted(0);
        else notifyDataSetChanged();
    }

    private boolean hasTitle() {
        return sectionTitle != null && !sectionTitle.isEmpty();
    }

    public void setOnClickListener(OnItemClickListener listener) {
        this.clickListener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return hasTitle() && position == 0 ? TYPE_SECTION_TITLE : 0;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_SECTION_TITLE) {
            View title = LayoutInflater.from(this.context).inflate(R.layout.cell_search_section, parent, false);
            return new TitleHolder(title);
        }
        View view = LayoutInflater.from(this.context).inflate(R.layout.cell_choose, parent, false);
        return new SuggestionHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof TitleHolder)
            return; //标题文案固定在布局里，无需绑定

        int dataPosition = position - (hasTitle() ? 1 : 0);
        if (dataPosition < 0 || dataPosition >= suggestionsList.size())
            return;
        ((SuggestionHolder) holder).show(suggestionsList.get(dataPosition));

        holder.itemView.setOnClickListener(view -> {
            if (clickListener != null) {
                clickListener.onItemClick(dataPosition);
            }
        });
    }

    @Override
    public int getItemCount() {
        int count = suggestionsList != null ? suggestionsList.size() : 0;
        return hasTitle() ? count + 1 : count;
    }

    public static class TitleHolder extends RecyclerView.ViewHolder {
        public TitleHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    public static class SuggestionHolder extends RecyclerView.ViewHolder {
        final TextView text_view;

        public SuggestionHolder(@NonNull View itemView) {
            super(itemView);
            text_view = itemView.findViewById(R.id.text);
        }

        public void show(String text) {
            text_view.setText(StringUtil.htmlToString(text));
        }
    }
}
