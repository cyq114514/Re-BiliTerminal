package com.RobinNotBad.BiliClient.api;

import com.RobinNotBad.BiliClient.model.InteractionVideoData;
import com.RobinNotBad.BiliClient.util.Logu;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class InteractionVideoApi {
    public static InteractionVideoData getEdgeInfo(long aid, String bvid, long graphVersion, long edgeId) throws JSONException, IOException {
        StringBuilder urlBuilder = new StringBuilder("https://api.bilibili.com/x/stein/edgeinfo_v2?");
        
        if (aid > 0) {
            urlBuilder.append("aid=").append(aid);
        } else if (bvid != null && !bvid.isEmpty()) {
            urlBuilder.append("bvid=").append(bvid);
        } else {
            throw new IllegalArgumentException("aid和bvid必须提供其中一个");
        }
        
        urlBuilder.append("&graph_version=").append(graphVersion);
        
        if (edgeId > 0) {
            urlBuilder.append("&edge_id=").append(edgeId);
        }
        
        String url = urlBuilder.toString();
        Logu.d("互动视频API", url);
        
        JSONObject response = NetWorkUtil.getJson(url, NetWorkUtil.webHeaders);
        
        int code = response.optInt("code", -1);
        if (code != 0) {
            String message = response.optString("message", "未知错误");
            throw new IOException("API返回错误: " + code + " - " + message);
        }
        
        JSONObject data = response.getJSONObject("data");
        return parseInteractionData(data);
    }
    
    private static InteractionVideoData parseInteractionData(JSONObject data) throws JSONException {
        InteractionVideoData result = new InteractionVideoData();
        
        result.title = data.optString("title", "");
        result.edgeId = data.optLong("edge_id", 0);
        result.isLeaf = data.optInt("is_leaf", 0);
        result.noTutorial = data.optInt("no_tutorial", 0);
        result.noBacktracking = data.optInt("no_backtracking", 0);
        result.noEvaluation = data.optInt("no_evaluation", 0);
        
        if (data.has("story_list") && !data.isNull("story_list")) {
            JSONArray storyArray = data.getJSONArray("story_list");
            result.storyList = new ArrayList<>();
            for (int i = 0; i < storyArray.length(); i++) {
                JSONObject storyObj = storyArray.getJSONObject(i);
                InteractionVideoData.InteractionStoryNode node = new InteractionVideoData.InteractionStoryNode();
                node.nodeId = storyObj.optLong("node_id", 0);
                node.edgeId = storyObj.optLong("edge_id", 0);
                node.title = storyObj.optString("title", "");
                node.cid = storyObj.optLong("cid", 0);
                node.startPos = storyObj.optLong("start_pos", 0);
                node.cover = storyObj.optString("cover", "");
                node.isCurrent = storyObj.optInt("is_current", 0);
                node.cursor = storyObj.optLong("cursor", 0);
                result.storyList.add(node);
            }
        }
        
        if (data.has("edges") && !data.isNull("edges")) {
            JSONObject edgesObj = data.getJSONObject("edges");
            result.edges = parseEdge(edgesObj);
        }
        
        if (data.has("preload") && !data.isNull("preload")) {
            JSONObject preloadObj = data.getJSONObject("preload");
            result.preload = parsePreload(preloadObj);
        }
        
        if (data.has("hidden_vars") && !data.isNull("hidden_vars")) {
            JSONArray varsArray = data.getJSONArray("hidden_vars");
            result.hiddenVars = new ArrayList<>();
            for (int i = 0; i < varsArray.length(); i++) {
                JSONObject varObj = varsArray.getJSONObject(i);
                InteractionVideoData.InteractionHiddenVar var = new InteractionVideoData.InteractionHiddenVar();
                var.value = varObj.optLong("value", 0);
                var.id = varObj.optString("id", "");
                var.idV2 = varObj.optString("id_v2", "");
                var.type = varObj.optInt("type", 1);
                var.isShow = varObj.optInt("is_show", 0);
                var.name = varObj.optString("name", "");
                var.skipOverwrite = varObj.optInt("skip_overwrite", 0);
                result.hiddenVars.add(var);
            }
        }
        
        return result;
    }
    
    private static InteractionVideoData.InteractionEdge parseEdge(JSONObject edgesObj) throws JSONException {
        InteractionVideoData.InteractionEdge edge = new InteractionVideoData.InteractionEdge();
        
        if (edgesObj.has("dimension") && !edgesObj.isNull("dimension")) {
            JSONObject dimObj = edgesObj.getJSONObject("dimension");
            InteractionVideoData.InteractionDimension dimension = new InteractionVideoData.InteractionDimension();
            dimension.width = dimObj.optInt("width", 0);
            dimension.height = dimObj.optInt("height", 0);
            dimension.rotate = dimObj.optInt("rotate", 0);
            dimension.sar = dimObj.optString("sar", "");
            edge.dimension = dimension;
        }
        
        if (edgesObj.has("questions") && !edgesObj.isNull("questions")) {
            JSONArray questionsArray = edgesObj.getJSONArray("questions");
            edge.questions = new ArrayList<>();
            for (int i = 0; i < questionsArray.length(); i++) {
                JSONObject questionObj = questionsArray.getJSONObject(i);
                InteractionVideoData.InteractionQuestion question = parseQuestion(questionObj);
                edge.questions.add(question);
            }
        }
        
        if (edgesObj.has("skin") && !edgesObj.isNull("skin")) {
            JSONObject skinObj = edgesObj.getJSONObject("skin");
            InteractionVideoData.InteractionSkin skin = new InteractionVideoData.InteractionSkin();
            skin.choiceImage = skinObj.optString("choice_image", "");
            skin.titleTextColor = skinObj.optString("title_text_color", "");
            skin.titleShadowColor = skinObj.optString("title_shadow_color", "");
            skin.titleShadowOffsetX = skinObj.optInt("title_shadow_offset_x", 0);
            skin.titleShadowOffsetY = skinObj.optInt("title_shadow_offset_y", 0);
            skin.titleShadowRadius = skinObj.optInt("title_shadow_radius", 0);
            skin.progressbarColor = skinObj.optString("progressbar_color", "");
            skin.progressbarShadowColor = skinObj.optString("progressbar_shadow_color", "");
            edge.skin = skin;
        }
        
        return edge;
    }
    
    private static InteractionVideoData.InteractionQuestion parseQuestion(JSONObject questionObj) throws JSONException {
        InteractionVideoData.InteractionQuestion question = new InteractionVideoData.InteractionQuestion();
        question.id = questionObj.optLong("id", 0);
        question.type = questionObj.optInt("type", 0);
        question.startTimeR = questionObj.optLong("start_time_r", 0);
        question.duration = questionObj.optLong("duration", -1);
        question.pauseVideo = questionObj.optInt("pause_video", 0);
        question.title = questionObj.optString("title", "");
        question.fadeInTime = questionObj.optLong("fade_in_time", 0);
        question.fadeOutTime = questionObj.optLong("fade_out_time", 0);
        
        if (questionObj.has("choices") && !questionObj.isNull("choices")) {
            JSONArray choicesArray = questionObj.getJSONArray("choices");
            question.choices = new ArrayList<>();
            for (int i = 0; i < choicesArray.length(); i++) {
                JSONObject choiceObj = choicesArray.getJSONObject(i);
                InteractionVideoData.InteractionChoice choice = parseChoice(choiceObj);
                question.choices.add(choice);
            }
        }
        
        return question;
    }
    
    private static InteractionVideoData.InteractionChoice parseChoice(JSONObject choiceObj) throws JSONException {
        InteractionVideoData.InteractionChoice choice = new InteractionVideoData.InteractionChoice();
        choice.id = choiceObj.optLong("id", 0);
        choice.platformAction = choiceObj.optString("platform_action", "");
        choice.nativeAction = choiceObj.optString("native_action", "");
        choice.condition = choiceObj.optString("condition", "");
        choice.cid = choiceObj.optLong("cid", 0);
        choice.x = choiceObj.optInt("x", 0);
        choice.y = choiceObj.optInt("y", 0);
        choice.textAlign = choiceObj.optInt("text_align", 0);
        choice.option = choiceObj.optString("option", "");
        choice.isDefault = choiceObj.optInt("is_default", 0);
        choice.isHidden = choiceObj.optInt("is_hidden", 0);
        
        if (choiceObj.has("selected") && !choiceObj.isNull("selected")) {
            JSONObject selectedObj = choiceObj.getJSONObject("selected");
            InteractionVideoData.InteractionAnimation anim = new InteractionVideoData.InteractionAnimation();
            anim.type = selectedObj.optString("type", "");
            anim.duration = selectedObj.optLong("duration", 0);
            choice.selected = anim;
        }
        
        if (choiceObj.has("submited") && !choiceObj.isNull("submited")) {
            JSONObject submitedObj = choiceObj.getJSONObject("submited");
            InteractionVideoData.InteractionAnimation anim = new InteractionVideoData.InteractionAnimation();
            anim.type = submitedObj.optString("type", "");
            anim.duration = submitedObj.optLong("duration", 0);
            choice.submited = anim;
        }
        
        return choice;
    }
    
    private static InteractionVideoData.InteractionPreload parsePreload(JSONObject preloadObj) throws JSONException {
        InteractionVideoData.InteractionPreload preload = new InteractionVideoData.InteractionPreload();
        
        if (preloadObj.has("video") && !preloadObj.isNull("video")) {
            JSONArray videoArray = preloadObj.getJSONArray("video");
            preload.video = new ArrayList<>();
            for (int i = 0; i < videoArray.length(); i++) {
                JSONObject videoObj = videoArray.getJSONObject(i);
                InteractionVideoData.InteractionPreloadVideo video = new InteractionVideoData.InteractionPreloadVideo();
                video.aid = videoObj.optLong("aid", 0);
                video.cid = videoObj.optLong("cid", 0);
                preload.video.add(video);
            }
        }
        
        return preload;
    }
}

