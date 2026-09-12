package com.RobinNotBad.BiliClient.adapter;

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

public class SearchSuggestionsAdapter extends RecyclerView.Adapter<SearchSuggestionsAdapter.SuggestionHolder> {

    final Context context;
    final ArrayList<String> suggestionsList;
    OnItemClickListener clickListener;

    public SearchSuggestionsAdapter(Context context, ArrayList<String> suggestionsList) {
        this.context = context;
        this.suggestionsList = suggestionsList;
    }

    public void setOnClickListener(OnItemClickListener listener) {
        this.clickListener = listener;
    }

    @NonNull
    @Override
    public SuggestionHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(this.context).inflate(R.layout.cell_choose, parent, false);
        return new SuggestionHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SuggestionHolder holder, int position) {
        if (position < 0 || position >= suggestionsList.size())
            return;
        holder.show(suggestionsList.get(position));

        holder.itemView.setOnClickListener(view -> {
            if (clickListener != null) {
                clickListener.onItemClick(position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return suggestionsList != null ? suggestionsList.size() : 0;
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
