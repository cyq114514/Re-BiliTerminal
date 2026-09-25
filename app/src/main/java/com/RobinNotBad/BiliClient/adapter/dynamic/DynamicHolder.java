package com.RobinNotBad.BiliClient.adapter.dynamic;

import static com.RobinNotBad.BiliClient.util.StringUtil.toWan;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.ImageViewerActivity;
import com.RobinNotBad.BiliClient.activity.ListChooseActivity;
import com.RobinNotBad.BiliClient.activity.base.BaseActivity;
import com.RobinNotBad.BiliClient.activity.dynamic.VoteActivity;
import com.RobinNotBad.BiliClient.activity.dynamic.send.SendDynamicActivity;
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity;
import com.RobinNotBad.BiliClient.adapter.article.ArticleCardHolder;
import com.RobinNotBad.BiliClient.adapter.video.VideoCardHolder;
import com.RobinNotBad.BiliClient.api.DynamicApi;
import com.RobinNotBad.BiliClient.model.ArticleCard;
import com.RobinNotBad.BiliClient.model.Dynamic;
import com.RobinNotBad.BiliClient.model.LiveRoom;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.GlideUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.StringUtil;
import com.RobinNotBad.BiliClient.util.TerminalContext;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DynamicHolder extends RecyclerView.ViewHolder {
    public static final int GO_TO_INFO_REQUEST = 71;
    /**动态操作菜单（置顶/可见范围/编辑）的请求码，结果在宿主Activity的onActivityResult里回调onDynamicOpResult*/
    public static final int DYNAMIC_OPS_REQUEST = 7131;
    private static Dynamic pendingOpsDynamic;

    public final TextView username;
    public final TextView content;
    public final TextView title;
    public TextView pubdate;
    public final ImageView avatar;
    public final LinearLayout extraCard;
    public final View itemView;
    public TextView item_dynamic_share, item_dynamic_delete;
    public TextView likeCount;
    public TextView item_dynamic_comment;
    public TextView dynamicVote;
    public View cell_dynamic_child;
    public final View cell_dynamic_video;
    public final View cell_dynamic_image;
    public final View cell_dynamic_article;
    public final boolean isChild;
    final BaseActivity mActivity;
    public ActivityResultLauncher<Intent> relayDynamicLauncher;
    public DynamicHolder childDynamicHolder;
    private VideoCardHolder videoCardHolder;
    private ArticleCardHolder articleCardHolder;
    private String lastAvatarUrl;
    private String lastImageUrl;

    public DynamicHolder(@NonNull View itemView, BaseActivity mActivity, boolean isChild) {
        super(itemView);
        this.itemView = itemView;
        this.isChild = isChild;
        this.mActivity = mActivity;
        if (isChild) {
            username = itemView.findViewById(R.id.child_username);
            content = itemView.findViewById(R.id.child_content);
            avatar = itemView.findViewById(R.id.child_avatar);
            title = itemView.findViewById(R.id.child_title);
            extraCard = itemView.findViewById(R.id.child_extraCard);
            this.cell_dynamic_video = extraCard.findViewById(R.id.dynamic_video_child);
            this.cell_dynamic_article = extraCard.findViewById(R.id.dynamic_article_child);
            this.cell_dynamic_image = extraCard.findViewById(R.id.dynamic_image_child);
        } else {
            username = itemView.findViewById(R.id.username);
            pubdate = itemView.findViewById(R.id.pubdate);
            content = itemView.findViewById(R.id.content);
            avatar = itemView.findViewById(R.id.avatar);
            title = itemView.findViewById(R.id.title);
            extraCard = itemView.findViewById(R.id.extraCard);
            item_dynamic_share = itemView.findViewById(R.id.item_dynamic_share);
            likeCount = itemView.findViewById(R.id.likes);
            item_dynamic_delete = itemView.findViewById(R.id.item_dynamic_delete);
            item_dynamic_comment = itemView.findViewById(R.id.item_dynamic_comment);
            dynamicVote = itemView.findViewById(R.id.dynamic_vote_extra);
            relayDynamicLauncher = mActivity.relayDynamicLauncher;
            this.cell_dynamic_child = extraCard.findViewById(R.id.dynamic_child);
            this.cell_dynamic_video = extraCard.findViewById(R.id.dynamic_video_extra);
            this.cell_dynamic_article = extraCard.findViewById(R.id.dynamic_article_extra);
            this.cell_dynamic_image = extraCard.findViewById(R.id.dynamic_image_extra);
        }
    }

    /**
     * 宿主Activity的onActivityResult转发到此处处理动态操作菜单的选择结果。
     * 编辑动态会再次以DYNAMIC_OPS_REQUEST拉起SendDynamicActivity，其完成结果（editOk）也在此处理。
     */
    public static void onDynamicOpResult(int requestCode, int resultCode, Intent data, BaseActivity activity) {
        if (requestCode != DYNAMIC_OPS_REQUEST) return;
        if (resultCode != Activity.RESULT_OK) {
            //取消选择/取消编辑时清掉挂起的动态引用，避免静态字段滞留（取消时data通常为null）
            pendingOpsDynamic = null;
            return;
        }
        if (data == null) return;
        String item = data.getStringExtra("item");
        String editOk = data.getStringExtra("editOk");
        if (editOk != null) {
            MsgUtil.showMsg("编辑成功~");
            return;
        }
        final Dynamic dynamic = pendingOpsDynamic;
        pendingOpsDynamic = null;
        if (dynamic == null || item == null) return;
        switch (item) {
            case "置顶动态":
            case "取消置顶": {
                boolean top = item.equals("置顶动态");
                CenterThreadPool.run(() -> {
                    try {
                        int code = DynamicApi.setTop(dynamic.dynamicId, top);
                        activity.runOnUiThread(() -> {
                            if (code == 0) {
                                dynamic.isTop = top;
                                MsgUtil.showMsg(top ? "置顶成功~" : "已取消置顶~");
                            } else MsgUtil.showMsg("操作失败：" + code);
                        });
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> MsgUtil.err(e));
                    }
                });
                break;
            }
            case "仅自己可见":
            case "设为所有人可见": {
                boolean privatePub = item.equals("仅自己可见");
                CenterThreadPool.run(() -> {
                    try {
                        int code = DynamicApi.setPrivatePub(dynamic.dynamicId, privatePub);
                        activity.runOnUiThread(() -> {
                            if (code == 0) {
                                dynamic.badgeText = privatePub ? "仅自己可见" : "";
                                MsgUtil.showMsg(privatePub ? "已设为仅自己可见" : "已设为所有人可见");
                            } else MsgUtil.showMsg("操作失败：" + code);
                        });
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> MsgUtil.err(e));
                    }
                });
                break;
            }
            case "编辑动态": {
                Intent intent = new Intent(activity, SendDynamicActivity.class);
                intent.putExtra("editId", dynamic.dynamicId);
                intent.putExtra("editText", dynamic.content == null ? "" : dynamic.content.toString());
                activity.startActivityForResult(intent, DYNAMIC_OPS_REQUEST);
                break;
            }
            default:
                MsgUtil.showMsg("未知操作：" + item);
        }
    }

    public static void removeDynamicFromList(List<Dynamic> dynamicList, int finalPosition,
                                             RecyclerView.Adapter<RecyclerView.ViewHolder> adapter) {
        removeDynamicFromList(dynamicList, finalPosition, adapter, false);
    }

    public static void removeDynamicFromList(List<Dynamic> dynamicList, int finalPosition,
                                             RecyclerView.Adapter<RecyclerView.ViewHolder> adapter, boolean showRecentUp) {
        removeDynamicFromList(dynamicList, finalPosition, adapter, showRecentUp ? 2 : 1);
    }

    public static void removeDynamicFromList(List<Dynamic> dynamicList, int finalPosition,
                                             RecyclerView.Adapter<RecyclerView.ViewHolder> adapter, int headerCount) {
        dynamicList.remove(finalPosition);
        adapter.notifyItemRemoved(finalPosition + headerCount);
        adapter.notifyItemRangeChanged(finalPosition + headerCount, dynamicList.size() - finalPosition);
    }

    public static View.OnLongClickListener getDeleteListener(Activity dynamicActivity, List<Dynamic> dynamicList,
                                                             int finalPosition, RecyclerView.Adapter<RecyclerView.ViewHolder> adapter) {
        return getDeleteListener(dynamicActivity, dynamicList, finalPosition, adapter, false);
    }

    public static View.OnLongClickListener getDeleteListener(Activity dynamicActivity, List<Dynamic> dynamicList,
                                                             int finalPosition, RecyclerView.Adapter<RecyclerView.ViewHolder> adapter, boolean showRecentUp) {
        return new View.OnLongClickListener() {
            private int longClickPosition = -1;
            private long longClickTime = -1;

            @Override
            public boolean onLongClick(View view) {
                if (dynamicList.get(finalPosition).canDelete) {
                    long currentTime = System.currentTimeMillis();
                    if (longClickPosition == finalPosition && currentTime - longClickTime < 10000) {
                        CenterThreadPool.run(() -> {
                            try {
                                int result = DynamicApi.deleteDynamic(dynamicList.get(finalPosition).dynamicId);
                                if (result == 0) {
                                    dynamicList.remove(finalPosition);
                                    dynamicActivity.runOnUiThread(() -> {
                                        int offset = showRecentUp ? 2 : 1;
                                        adapter.notifyItemRemoved(finalPosition + offset);
                                        adapter.notifyItemRangeChanged(finalPosition + offset,
                                                dynamicList.size() - finalPosition);
                                        longClickPosition = -1;
                                        MsgUtil.showMsg("删除成功~");
                                    });
                                } else {
                                    String msg = "操作失败：" + result;
                                    switch (result) {
                                        case 500404:
                                            msg = "已经删除过了哦~";
                                            break;
                                        case 500406:
                                            msg = "不是自己的动态！";
                                            break;
                                    }
                                    String finalMsg = msg;
                                    dynamicActivity.runOnUiThread(() -> MsgUtil.showMsg(finalMsg));
                                }
                            } catch (IOException e) {
                                dynamicActivity.runOnUiThread(() -> MsgUtil.err(e));
                            }
                        });
                    } else {
                        longClickPosition = finalPosition;
                        longClickTime = currentTime;
                        MsgUtil.showMsg("再次长按删除");
                    }
                }
                return true;
            }
        };
    }

    public static View.OnLongClickListener getDeleteListener(Activity dynamicActivity, Dynamic dynamic) {
        return new View.OnLongClickListener() {
            private long longClickTime = -1;

            @Override
            public boolean onLongClick(View view) {
                if (dynamic.canDelete) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - longClickTime < 10000) {
                        CenterThreadPool.run(() -> {
                            try {
                                int result = DynamicApi.deleteDynamic(dynamic.dynamicId);
                                if (result == 0) {
                                    dynamicActivity.runOnUiThread(() -> {
                                        dynamicActivity.setResult(Activity.RESULT_OK,
                                                dynamicActivity.getIntent().getExtras() != null
                                                        ? new Intent()
                                                        .putExtras(dynamicActivity.getIntent().getExtras())
                                                        : new Intent());
                                        dynamicActivity.finish();
                                        MsgUtil.showMsg("删除成功~");
                                    });
                                } else {
                                    String msg = "操作失败：" + result;
                                    switch (result) {
                                        case 500404:
                                            msg = "已经删除过了哦~";
                                            break;
                                        case 500406:
                                            msg = "不是自己的动态！";
                                            break;
                                    }
                                    String finalMsg = msg;
                                    dynamicActivity.runOnUiThread(() -> MsgUtil.showMsg(finalMsg));
                                }
                            } catch (IOException e) {
                                dynamicActivity.runOnUiThread(() -> MsgUtil.err(e));
                            }
                        });
                    } else {
                        longClickTime = currentTime;
                        MsgUtil.showMsg("再次长按删除");
                    }
                }
                return true;
            }
        };
    }

    @SuppressLint({"SetTextI18n", "ClickableViewAccessibility"})
    public void showDynamic(Context context, Dynamic dynamic, boolean clickable) { // 公用的显示函数 这样修改和调用都方便
        if (!TextUtils.isEmpty(dynamic.title)) {
            title.setVisibility(View.VISIBLE);
            title.setText(dynamic.title);
        } else
            title.setVisibility(View.GONE);

        username.setText(dynamic.userInfo.name);
        if (!dynamic.userInfo.vip_nickname_color.isEmpty()) {
            username.setTextColor(Color.parseColor(dynamic.userInfo.vip_nickname_color));
        } else {
            username.setTextColor(0xFFFFFFFF);
        }
        if (pubdate != null) {
            //pub_action（如"参与了投票"）优先于发布时间展示，其后拼icon_badge（如"仅自己可见"）
            StringBuilder dateText = new StringBuilder();
            if (!TextUtils.isEmpty(dynamic.pubAction)) dateText.append(dynamic.pubAction);
            else dateText.append(dynamic.pubTime);
            if (!TextUtils.isEmpty(dynamic.badgeText)) dateText.append(" · ").append(dynamic.badgeText);
            pubdate.setText(dateText.toString());
        }
        if (dynamic.content != null && !TextUtils.isEmpty(dynamic.content)) {
            content.setVisibility(View.VISIBLE);
            content.setText(dynamic.content);
            StringUtil.setCopy(content);
            content.setOnTouchListener(new StringUtil.ClickableSpanTouchListener());
        } else
            content.setVisibility(View.GONE);

        if (!dynamic.userInfo.avatar.equals(lastAvatarUrl)) {
            lastAvatarUrl = dynamic.userInfo.avatar;
            Glide.with(BiliTerminal.context).asDrawable().load(GlideUtil.url(dynamic.userInfo.avatar))
                    .transition(GlideUtil.getTransitionOptions())
                    .placeholder(R.mipmap.akari)
                    .apply(RequestOptions.circleCropTransform())
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .into(avatar);
        }

        avatar.setOnClickListener(view -> {
            Intent intent = new Intent();
            intent.setClass(context, UserInfoActivity.class);
            intent.putExtra("mid", dynamic.userInfo.mid);
            context.startActivity(intent);
        });

        boolean isPgc = false;
        for (View view1 : Arrays.asList(cell_dynamic_video, cell_dynamic_child, cell_dynamic_image,
                cell_dynamic_article)) {
            if (view1 != null) {
                view1.setVisibility(View.GONE);
            }
        }
        if (dynamic.major_type != null)
            switch (dynamic.major_type) {
                case "MAJOR_TYPE_PGC":
                    isPgc = true;
                case "MAJOR_TYPE_ARCHIVE":
                case "MAJOR_TYPE_UGC_SEASON":
                    VideoCard childVideoCard = (VideoCard) dynamic.major_object;
                    if (videoCardHolder == null) {
                        videoCardHolder = new VideoCardHolder(cell_dynamic_video);
                    }
                    videoCardHolder.showVideoCard(childVideoCard, context);
                    boolean finalIsPgc = isPgc;
                    cell_dynamic_video.setOnClickListener(view -> TerminalContext.getInstance()
                            .enterVideoDetailPage(context, childVideoCard.aid, "", finalIsPgc ? "media" : null));
                    cell_dynamic_video.setVisibility(View.VISIBLE);
                    break;

                case "MAJOR_TYPE_LIVE":
                case "MAJOR_TYPE_LIVE_RCMD":
                    LiveRoom liveRoom = (LiveRoom) dynamic.major_object;
                    VideoCard childLiveCard = new VideoCard();
                    childLiveCard.title = liveRoom.title;
                    childLiveCard.cover = liveRoom.cover;
                    childLiveCard.upName = liveRoom.uname;
                    childLiveCard.view = "";
                    childLiveCard.type = "live";

                    if (videoCardHolder == null) {
                        videoCardHolder = new VideoCardHolder(cell_dynamic_video);
                    }
                    videoCardHolder.showVideoCard(childLiveCard, context);
                    cell_dynamic_video.setOnClickListener(
                            view -> TerminalContext.getInstance().enterLiveDetailPage(context, liveRoom.roomid));
                    cell_dynamic_video.setVisibility(View.VISIBLE);
                    break;

                case "MAJOR_TYPE_ARTICLE":
                    ArticleCard articleCard = (ArticleCard) dynamic.major_object;
                    if (articleCardHolder == null) {
                        articleCardHolder = new ArticleCardHolder(cell_dynamic_article);
                    }
                    articleCardHolder.showArticleCard(articleCard, context);
                    cell_dynamic_article.setOnClickListener(
                            view -> TerminalContext.getInstance().enterArticleDetailPage(context, articleCard.id));
                    cell_dynamic_article.setVisibility(View.VISIBLE);
                    break;

                case "MAJOR_TYPE_DRAW":
                case "MAJOR_TYPE_OPUS":
                    ArrayList<String> pictureList;
                    if (dynamic.major_object instanceof ArrayList) {
                        pictureList = (ArrayList<String>) dynamic.major_object;
                    } else {
                        pictureList = new ArrayList<>();
                    }

                    if (!pictureList.isEmpty()) {
                        ImageView imageView = cell_dynamic_image.findViewById(R.id.imageView);
                        String firstImageUrl = pictureList.get(0);
                        if (!firstImageUrl.equals(lastImageUrl)) {
                            lastImageUrl = firstImageUrl;
                            Glide.with(BiliTerminal.context).asDrawable().load(GlideUtil.url(firstImageUrl))
                                    .transition(GlideUtil.getTransitionOptions())
                                    .placeholder(R.mipmap.placeholder)
                                    .centerCrop()
                                    .format(DecodeFormat.PREFER_RGB_565)
                                    .sizeMultiplier(0.85f)
                                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                                    .into(imageView);
                        }
                        TextView textView = cell_dynamic_image.findViewById(R.id.imageCount);
                        textView.setText("共" + pictureList.size() + "张图片");
                        imageView.setOnClickListener(view -> {
                            Intent intent = new Intent();
                            intent.setClass(context, ImageViewerActivity.class);
                            intent.putExtra("imageList", pictureList);
                            context.startActivity(intent);
                        });
                        cell_dynamic_image.setVisibility(View.VISIBLE);
                    }
                    break;
            }

        if (dynamic.major_object == null && dynamic.dynamic_forward == null)
            extraCard.setVisibility(View.GONE); // 这部分在adapter里
        else
            extraCard.setVisibility(View.VISIBLE);

        //投票卡片（module_additional ADDITIONAL_TYPE_VOTE）
        if (dynamicVote != null) {
            if (dynamic.vote != null && dynamic.vote.voteId != 0) {
                dynamicVote.setVisibility(View.VISIBLE);
                dynamicVote.setText("🗳 " + (TextUtils.isEmpty(dynamic.vote.title) ? "参与投票" : dynamic.vote.title)
                        + "　" + toWan(dynamic.vote.joinNum) + "人参与");
                dynamicVote.setOnClickListener(view -> {
                    Intent intent = new Intent();
                    intent.setClass(context, VoteActivity.class);
                    intent.putExtra("voteId", dynamic.vote.voteId);
                    intent.putExtra("dynamicId", dynamic.dynamicId);
                    context.startActivity(intent);
                });
            } else {
                dynamicVote.setVisibility(View.GONE);
            }
        }

        if (clickable) {
            content.setMaxLines(5);
            if (dynamic.dynamicId != 0) {
                (isChild ? itemView.findViewById(R.id.dynamic_child) : itemView).setOnClickListener(view -> {
                    if (context instanceof Activity) {
                        TerminalContext.getInstance().enterDynamicDetailPageForResult((Activity) context,
                                dynamic.dynamicId, getAdapterPosition(), GO_TO_INFO_REQUEST);
                    } else {
                        TerminalContext.getInstance().enterDynamicDetailPage(context, dynamic.dynamicId,
                                getAdapterPosition());
                    }
                });
                content.setOnClickListener(view -> {
                    View targetView = (isChild ? itemView.findViewById(R.id.dynamic_child) : itemView);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                        targetView.callOnClick();
                    } else {
                        targetView.performClick();
                    }
                });
            }
        } else {
            content.setMaxLines(999);
        }
        content.setEllipsize(TextUtils.TruncateAt.END);

        View.OnClickListener onRelayClick = view -> {
            if (relayDynamicLauncher == null) {
                return;
            }
            Intent intent = new Intent();
            intent.setClass(mActivity, SendDynamicActivity.class);
            intent.putExtra("dynamicId", dynamic.dynamicId);
            //转发自动引用所需的信息，SendDynamicActivity完成时会原样回传
            if (dynamic.userInfo != null) {
                intent.putExtra("forwardAuthorName", dynamic.userInfo.name);
                intent.putExtra("forwardAuthorMid", dynamic.userInfo.mid);
            }
            if (dynamic.content != null) intent.putExtra("forwardContentText", dynamic.content.toString());
            TerminalContext.getInstance().setForwardContent(dynamic);
            relayDynamicLauncher.launch(intent);
        };
        if (item_dynamic_share != null && clickable)
            item_dynamic_share.setOnClickListener(onRelayClick);

        View.OnClickListener onDeleteClick = view -> MsgUtil.showMsg("长按删除");
        if (item_dynamic_delete != null) {
            if (dynamic.canDelete && clickable) {
                //自己的动态：点击弹出操作菜单（置顶/可见范围/编辑），长按删除保持不变
                onDeleteClick = view -> {
                    pendingOpsDynamic = dynamic;
                    ArrayList<String> ops = new ArrayList<>();
                    ops.add(dynamic.isTop ? "取消置顶" : "置顶动态");
                    ops.add(dynamic.isOnlySelf() ? "设为所有人可见" : "仅自己可见");
                    if (dynamic.major_object == null && dynamic.dynamic_forward == null) ops.add("编辑动态");
                    ((Activity) context).startActivityForResult(
                            new Intent(context, ListChooseActivity.class)
                                    .putExtra("title", "动态操作")
                                    .putExtra("items", ops),
                            DYNAMIC_OPS_REQUEST);
                };
            }
            item_dynamic_delete.setOnClickListener(onDeleteClick);
            item_dynamic_delete.setVisibility(View.GONE);
        }

        //评论数与入口
        if (item_dynamic_comment != null) {
            if (dynamic.stats != null && dynamic.stats.reply > 0 && dynamic.dynamicId != 0) {
                item_dynamic_comment.setVisibility(View.VISIBLE);
                item_dynamic_comment.setText(toWan(dynamic.stats.reply));
                item_dynamic_comment.setOnClickListener(view -> {
                    if (context instanceof Activity)
                        TerminalContext.getInstance().enterDynamicDetailPage((Activity) context,
                                dynamic.dynamicId, getAdapterPosition(), 1L);
                    else
                        TerminalContext.getInstance().enterDynamicDetailPage(context, dynamic.dynamicId, getAdapterPosition());
                });
            } else {
                item_dynamic_comment.setVisibility(View.GONE);
            }
        }

        if (likeCount != null) {
            if (dynamic.stats != null) {
                if (dynamic.stats.liked) { // 这里，还有下面，一定要加else！否则会导致错乱
                    likeCount.setTextColor(Color.rgb(0xfe, 0x67, 0x9a));
                    likeCount.setCompoundDrawablesWithIntrinsicBounds(
                            ContextCompat.getDrawable(context, R.drawable.icon_reply_like1), null, null, null);
                } else {
                    likeCount.setTextColor(Color.rgb(0xff, 0xff, 0xff));
                    likeCount.setCompoundDrawablesWithIntrinsicBounds(
                            ContextCompat.getDrawable(context, R.drawable.icon_reply_like0), null, null, null);
                }
                likeCount.setText(toWan(dynamic.stats.like));
            } else {
                likeCount.setVisibility(View.GONE);
            }
            likeCount.setOnClickListener(view -> CenterThreadPool.run(() -> {
                if (!dynamic.stats.liked) {
                    try {
                        if (DynamicApi.likeDynamic(dynamic.dynamicId, true) == 0) {
                            dynamic.stats.liked = true;
                            ((Activity) context).runOnUiThread(() -> {
                                MsgUtil.showMsg("点赞成功");
                                likeCount.setText(toWan(++dynamic.stats.like));
                                likeCount.setTextColor(Color.rgb(0xfe, 0x67, 0x9a));
                                likeCount.setCompoundDrawablesWithIntrinsicBounds(
                                        ContextCompat.getDrawable(context, R.drawable.icon_reply_like1), null, null,
                                        null);
                            });
                        } else
                            ((Activity) context).runOnUiThread(() -> MsgUtil.showMsg("点赞失败"));
                    } catch (IOException e) {
                        MsgUtil.err(e);
                    }
                } else {
                    try {
                        if (DynamicApi.likeDynamic(dynamic.dynamicId, false) == 0) {
                            dynamic.stats.liked = false;
                            ((Activity) context).runOnUiThread(() -> {
                                MsgUtil.showMsg("取消成功");
                                likeCount.setText(toWan(--dynamic.stats.like));
                                likeCount.setTextColor(Color.rgb(0xff, 0xff, 0xff));
                                likeCount.setCompoundDrawablesWithIntrinsicBounds(
                                        ContextCompat.getDrawable(context, R.drawable.icon_reply_like0), null, null,
                                        null);
                            });
                        } else
                            ((Activity) context).runOnUiThread(() -> MsgUtil.showMsg("取消失败"));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }));
        }
    }
}
