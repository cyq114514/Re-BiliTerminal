package com.RobinNotBad.BiliClient.util;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.ui.theme.ThemeApplier;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 自适应弹窗：替代系统 {@code AlertDialog}，解决「应用内把界面大小/字号调大后弹窗被裁」。
 *
 * <p><b>为什么要自己实现</b>：系统 AlertDialog 的布局（appcompat 的
 * {@code abc_alert_dialog_material.xml}）从根到正文全是 {@code wrap_content}，既没有
 * maxHeight 也没有 weight；而窗口尺寸按系统原始屏幕计算，不跟应用内的
 * {@code densityDpi} 覆盖走。于是把「界面大小」调大后，弹窗内容变大、窗口不变，
 * 超出的部分被屏幕直接裁掉——表现为正文看不到结尾、按钮点不到、列表选不到最后几集。
 *
 * <p><b>这里怎么做</b>：窗口宽度固定为屏宽 92%、高度上限为屏高 86%（不用系统的最小宽度
 * 65% 规则）；标题与按钮区常驻，正文/单选列表按实测高度分配，超过上限就钉死高度交给
 * 内部滚动。这样无论字号放大多少，按钮永远可见、内容永远能滚到结尾。
 *
 * <p><b>按钮顺序</b>：从左到右依次是 neutral、negative、positive，与系统 AlertDialog 一致。
 * <b>回调顺序</b>：先执行点击回调、再 dismiss。{@code SettingsAdapter} 依赖
 * onDismiss 里"库里的值没变就把开关拨回去"，若先 dismiss 再回调会误判成取消。
 */
public final class AdaptiveDialog {

    /** 窗口宽度占屏幕宽度的比例，两侧各留 4% 给阴影与手指误触 */
    private static final float WIDTH_RATIO = 0.92f;
    /** 窗口高度占屏幕高度的比例，留出状态栏/导航栏余量 */
    private static final float HEIGHT_RATIO = 0.86f;

    private AdaptiveDialog() {
    }

    public static class Builder {

        private final Context context;
        private final Dialog dialog;
        private final View root;
        private final TextView titleView;
        private final TextView messageView;
        private final FrameLayout customContainer;
        private final ScrollView scrollView;
        private final RecyclerView listView;
        private final LinearLayout buttonBar;
        private final List<ButtonSpec> buttons = new ArrayList<>(3);

        private CharSequence title;
        private CharSequence message;
        private boolean cancelable = true;
        private String[] items;
        private int checkedIndex = -1;
        private DialogInterface.OnClickListener itemPickListener;
        private DialogInterface.OnDismissListener dismissListener;
        private DialogInterface.OnCancelListener cancelListener;

        public Builder(@NonNull Context context) {
            //对话框必须挂在 Activity 上，用 Application context 会拿不到 window token
            this.context = unwrapActivity(context);
            this.dialog = new Dialog(this.context, R.style.Dialog_Adaptive);
            LayoutInflater inflater = LayoutInflater.from(this.context);
            this.root = inflater.inflate(R.layout.dialog_adaptive, null);
            this.titleView = root.findViewById(R.id.dialog_title);
            this.messageView = root.findViewById(R.id.dialog_message);
            this.customContainer = root.findViewById(R.id.dialog_custom);
            this.scrollView = root.findViewById(R.id.dialog_scroll);
            this.listView = root.findViewById(R.id.dialog_list);
            this.buttonBar = root.findViewById(R.id.dialog_buttons);
        }

        public Builder setTitle(@Nullable CharSequence title) {
            this.title = title;
            return this;
        }

        public Builder setTitle(@StringRes int titleRes) {
            return setTitle(context.getString(titleRes));
        }

        public Builder setMessage(@Nullable CharSequence message) {
            this.message = message;
            return this;
        }

        public Builder setMessage(@StringRes int messageRes) {
            return setMessage(context.getString(messageRes));
        }

        /** 正文位置放自定义控件（如输入框），与消息文本可同时存在 */
        public Builder setView(@Nullable View view) {
            customContainer.removeAllViews();
            if (view != null) {
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) parent.removeView(view);
                customContainer.addView(view);
            }
            return this;
        }

        public Builder setCancelable(boolean cancelable) {
            this.cancelable = cancelable;
            return this;
        }

        /**
         * 单选列表。点击条目只回调、不自动关闭（与系统 setSingleChoiceItems 行为一致，
         * 由调用方决定是否 dismiss）。
         */
        public Builder setSingleChoiceItems(@Nullable String[] items, int checkedIndex,
                                            @Nullable DialogInterface.OnClickListener listener) {
            this.items = items;
            this.checkedIndex = checkedIndex;
            this.itemPickListener = listener;
            return this;
        }

        public Builder setPositiveButton(@Nullable CharSequence text,
                                         @Nullable DialogInterface.OnClickListener listener) {
            buttons.add(new ButtonSpec(text, 4, DialogInterface.BUTTON_POSITIVE, listener));
            return this;
        }

        public Builder setPositiveButton(@StringRes int textRes,
                                         @Nullable DialogInterface.OnClickListener listener) {
            return setPositiveButton(context.getString(textRes), listener);
        }

        public Builder setNegativeButton(@Nullable CharSequence text,
                                         @Nullable DialogInterface.OnClickListener listener) {
            buttons.add(new ButtonSpec(text, 3, DialogInterface.BUTTON_NEGATIVE, listener));
            return this;
        }

        public Builder setNegativeButton(@StringRes int textRes,
                                         @Nullable DialogInterface.OnClickListener listener) {
            return setNegativeButton(context.getString(textRes), listener);
        }

        public Builder setNeutralButton(@Nullable CharSequence text,
                                        @Nullable DialogInterface.OnClickListener listener) {
            buttons.add(new ButtonSpec(text, 2, DialogInterface.BUTTON_NEUTRAL, listener));
            return this;
        }

        public Builder setNeutralButton(@StringRes int textRes,
                                        @Nullable DialogInterface.OnClickListener listener) {
            return setNeutralButton(context.getString(textRes), listener);
        }

        public Builder setOnDismissListener(@Nullable DialogInterface.OnDismissListener listener) {
            this.dismissListener = listener;
            return this;
        }

        public Builder setOnCancelListener(@Nullable DialogInterface.OnCancelListener listener) {
            this.cancelListener = listener;
            return this;
        }

        public Dialog create() {
            bindContent();
            dialog.setContentView(root);

            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setGravity(Gravity.CENTER);
            }

            dialog.setCancelable(cancelable);
            dialog.setCanceledOnTouchOutside(cancelable);
            if (dismissListener != null) dialog.setOnDismissListener(dismissListener);
            if (cancelListener != null) dialog.setOnCancelListener(cancelListener);

            applySize();
            return dialog;
        }

        public Dialog show() {
            Dialog d = create();
            d.show();
            return d;
        }

        // ---------------------------------------------------------------- 内部实现

        private void bindContent() {
            boolean hasTitle = title != null && title.length() > 0;
            titleView.setText(hasTitle ? title : null);
            titleView.setVisibility(hasTitle ? View.VISIBLE : View.GONE);

            boolean listMode = items != null && items.length > 0;

            boolean hasMessage = message != null && message.length() > 0;
            messageView.setText(hasMessage ? message : null);
            messageView.setVisibility(hasMessage ? View.VISIBLE : View.GONE);

            boolean hasCustom = customContainer.getChildCount() > 0;
            customContainer.setVisibility(hasCustom ? View.VISIBLE : View.GONE);

            //正文与列表二选一：列表需要自己的滚动容器，不能再套一层 ScrollView
            scrollView.setVisibility(listMode || (!hasMessage && !hasCustom) ? View.GONE : View.VISIBLE);
            listView.setVisibility(listMode ? View.VISIBLE : View.GONE);
            if (listMode) {
                listView.setLayoutManager(new LinearLayoutManager(context));
                listView.setAdapter(new ChoiceAdapter(items, checkedIndex, (position, adapter) -> {
                    checkedIndex = position;
                    //只刷新旧两行：整表 notifyDataSetChanged 在番剧上百集时会把所有可见行重绑一遍，
                    //低端机/手表上点选会有明显顿挫感
                    adapter.select(position);
                    if (itemPickListener != null) itemPickListener.onClick(dialog, position);
                }));
            }

            buildButtons();
        }

        private void buildButtons() {
            buttonBar.removeAllViews();
            if (buttons.isEmpty()) {
                buttonBar.setVisibility(View.GONE);
                return;
            }
            buttonBar.setVisibility(View.VISIBLE);
            //按 neutral(2) → negative(3) → positive(4) 排序，和系统弹窗一致的视觉顺序
            Collections.sort(buttons, (a, b) -> Integer.compare(a.order, b.order));

            LayoutInflater inflater = LayoutInflater.from(context);
            for (ButtonSpec spec : buttons) {
                if (spec.text == null || spec.text.length() == 0) continue;
                MaterialButton button = (MaterialButton) inflater.inflate(
                        R.layout.button_dialog_text, buttonBar, false);
                //弹窗不在 Activity 的内容树里，强调色按钮需要单独应用一次
                ThemeApplier.apply(button);
                button.setText(spec.text);
                //高度写死：MaterialButton 的 wrap_content 会被 TextButton 样式的 inset 与
                //padding 叠加压扁，界面放大后文字只剩中间一条（实测 dpi 1.6 时可复现）
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, Math.max(dp(48), button.getMinHeight()), 1f);
                int gap = dp(4);
                lp.setMargins(gap, 0, gap, 0);
                button.setLayoutParams(lp);
                button.setOnClickListener(v -> {
                    //先回调再关闭：调用方可能在回调里落库，onDismiss 需要看到新值
                    if (spec.listener != null) spec.listener.onClick(dialog, spec.which);
                    dialog.dismiss();
                });
                buttonBar.addView(button);
            }
        }

        /**
         * 按屏幕可用尺寸定窗口大小。
         *
         * <p>分三步：先把内容区藏起来量出「标题 + 按钮 + 内边距」的固定开销；
         * 再量整张卡片的自然总高；最后总高与上限取小作为窗口高，
         * 并把内容区高度钉成「窗口高 − 固定开销 − 内容区上边距」。
         *
         * <p>注意必须减掉内容区自己的 topMargin：藏起来测量时 GONE 的子项连同
         * margin 一起不参与布局，不减就会让卡片比窗口高出这几 dp，底部按钮被窗口裁掉
         * （dpi 1.6 实测可复现，按钮文字只剩中间一条）。
         */
        private void applySize() {
            Window window = dialog.getWindow();
            if (window == null) return;
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;

            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            int maxWidth = (int) (metrics.widthPixels * WIDTH_RATIO);
            int maxHeight = (int) (metrics.heightPixels * HEIGHT_RATIO);
            if (maxWidth <= 0 || maxHeight <= 0) return;

            boolean listMode = items != null && items.length > 0;
            View content = listMode ? listView : scrollView;
            ViewGroup.LayoutParams lp = content.getLayoutParams();
            int contentMarginTop = lp instanceof ViewGroup.MarginLayoutParams
                    ? ((ViewGroup.MarginLayoutParams) lp).topMargin : 0;

            //1) 固定开销：把内容区藏起来测一次
            int contentVisibility = content.getVisibility();
            content.setVisibility(View.GONE);
            root.measure(exactly(maxWidth), unspecified());
            int chromeHeight = root.getMeasuredHeight();

            //2) 自然总高：内容区按 wrap_content 参与测量（消息型与列表型同一套逻辑，
            //   不再对列表行高做估算——估算偏差正是窗口偏矮的来源）
            content.setVisibility(contentVisibility);
            int naturalHeight;
            if (contentVisibility == View.GONE) {
                naturalHeight = chromeHeight;
            } else {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                content.setLayoutParams(lp);
                root.measure(exactly(maxWidth), unspecified());
                naturalHeight = root.getMeasuredHeight();
            }

            int windowHeight = Math.min(naturalHeight, maxHeight);

            //3) 内容区吃掉剩余高度，超出部分内部滚动，按钮永远留在窗口内
            if (contentVisibility != View.GONE) {
                int allowed = windowHeight - chromeHeight - contentMarginTop;
                if (allowed < 1) allowed = 1;
                lp.height = allowed;
                content.setLayoutParams(lp);
            }

            window.setLayout(maxWidth, windowHeight);
        }

        private int dp(int value) {
            return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
        }
    }

    // ------------------------------------------------------------------ 辅助类

    private static final class ButtonSpec {
        final CharSequence text;
        /** 2=neutral 3=negative 4=positive，仅用于排序 */
        final int order;
        /** 回传给调用方的 which，取系统规定的 BUTTON_POSITIVE/NEGATIVE/NEUTRAL */
        final int which;
        final DialogInterface.OnClickListener listener;

        ButtonSpec(CharSequence text, int order, int which, DialogInterface.OnClickListener listener) {
            this.text = text;
            this.order = order;
            this.which = which;
            this.listener = listener;
        }
    }

    private interface AdapterPick {
        void onPick(int position, @NonNull ChoiceAdapter adapter);
    }

    private static final class ChoiceAdapter extends RecyclerView.Adapter<ChoiceAdapter.Holder> {

        private final String[] items;
        private final AdapterPick pick;
        private int checked;

        ChoiceAdapter(String[] items, int checked, AdapterPick pick) {
            this.items = items;
            this.checked = checked;
            this.pick = pick;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_dialog_choice, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.text.setText(items[position]);
            holder.radio.setChecked(position == checked);
            holder.itemView.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                pick.onPick(pos, this);
            });
        }

        /**
         * 切换选中项并只刷新受影响的两行。
         *
         * <p>不用 notifyDataSetChanged：它会让 RecyclerView 把所有可见行重新绑定一遍，
         * 而这里实际只变了两行（旧选中取消、新选中打勾）。番剧整季上百集时差别明显。
         */
        void select(int position) {
            int old = checked;
            checked = position;
            if (old == position) return;
            if (old >= 0 && old < items.length) notifyItemChanged(old);
            if (position >= 0 && position < items.length) notifyItemChanged(position);
        }

        @Override
        public int getItemCount() {
            return items.length;
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final com.google.android.material.radiobutton.MaterialRadioButton radio;
            final TextView text;

            Holder(@NonNull View itemView) {
                super(itemView);
                radio = itemView.findViewById(R.id.choice_radio);
                text = itemView.findViewById(R.id.choice_text);
            }
        }
    }

    private static int exactly(int size) {
        return View.MeasureSpec.makeMeasureSpec(Math.max(0, size), View.MeasureSpec.EXACTLY);
    }

    private static int unspecified() {
        return View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
    }

    /**
     * 沿 ContextWrapper 链找到 Activity：Dialog 必须用 Activity 的 window token，
     * 而 RecyclerView 的 itemView context、ContextThemeWrapper 都不是 Activity 本身。
     */
    private static Context unwrapActivity(Context context) {
        Context current = context;
        for (int i = 0; i < 8; i++) {
            if (current instanceof Activity) return current;
            if (!(current instanceof ContextWrapper)) break;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == null || base == current) break;
            current = base;
        }
        return context;
    }
}
