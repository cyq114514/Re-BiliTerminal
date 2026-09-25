package com.RobinNotBad.BiliClient.activity.dynamic;

import android.os.Bundle;

import com.RobinNotBad.BiliClient.api.DynamicApi;
import com.RobinNotBad.BiliClient.model.Dynamic;

import java.util.List;

/**
 * 话题动态页：复用DynamicActivity的列表框架，改为按话题名拉取 feed/topic 流。
 * 入口为动态正文里的 #话题# 富文本节点。
 */
public class TopicDynamicActivity extends DynamicActivity {

    private long topicId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        topicId = getIntent().getLongExtra("topicId", 0);
        super.onCreate(savedInstanceState);
    }

    @Override
    public boolean isTopicMode() {
        return true;
    }

    @Override
    protected long fetchPage(List<Dynamic> out, long offset, String type) throws Exception {
        return DynamicApi.getTopicDynamicList(out, offset, topicId);
    }
}
