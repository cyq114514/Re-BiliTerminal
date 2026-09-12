package com.RobinNotBad.BiliClient.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.util.MsgUtil;

public class GetIntentActivity extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String type = intent.getStringExtra("type");

        if (type != null) switch (intent.getStringExtra("type")) {
            case "video_av":
                BiliTerminal.jumpToVideo(this, intent.getLongExtra("content", 0));
                break;
            case "video_bv":
                BiliTerminal.jumpToVideo(this, intent.getStringExtra("content"));
                break;
            case "article":
                BiliTerminal.jumpToArticle(this, intent.getLongExtra("content", 0));
                break;
            case "user":
                BiliTerminal.jumpToUser(this, intent.getLongExtra("content", 0));
                break;
            default:
                MsgUtil.showMsgLong("不支持打开：" + type);
                break;
        }

        Uri uri = intent.getData();
        if (uri != null) {
            String host = uri.getHost();
            Log.e("debug-host", host);

            //外部链接不可信：host 可能为 null（例如 bilibili: 这种无 host 的深链），
            //pathSegment 也可能不是数字，这里直接崩会把整个链接入口炸掉
            try {
                if (host == null) {
                    MsgUtil.showMsgLong("不支持打开：" + uri);
                } else switch (host) {
                    case "video":
                        BiliTerminal.jumpToVideo(this, Long.parseLong(uri.getLastPathSegment()));
                        break;
                    case "article":
                        BiliTerminal.jumpToArticle(this, Long.parseLong(uri.getLastPathSegment()));
                        break;
                    default:
                        MsgUtil.showMsgLong("不支持打开：" + host);
                        break;
                }
            } catch (NumberFormatException e) {
                MsgUtil.showMsgLong("链接格式不支持：" + uri);
            }
        }

        finish();
    }
}
