package com.RobinNotBad.BiliClient.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.model.SettingSection;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

//设置项
//2024-06-06

public class SettingsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    final Context context;
    final List<SettingSection> list;
    private static final Map<String, Integer> typeMap = new HashMap<>() {
        {
            put("divider", -1);
            put("title", -2);
            put("switch", 0);
            //带警告的开关：复用 SwitchHolder，只是在切换时先弹确认。
            //必须登记进 typeMap —— getItemViewType 直接对 get() 的结果拆箱，
            //未登记的类型会返回 null 并在这里 NPE
            put("switch_warn", 0);
            put("choose", 1);
            put("input_int", 2);
            put("input_float", 3);
            put("input_string", 4);

        }
    };

    public SettingsAdapter(Context context, List<SettingSection> list) {
        this.context = context;
        this.list = list;
    }

    @Override
    public int getItemViewType(int position) {
        return typeMap.get(list.get(position).type);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        switch (viewType) {
            case 1:
                return new ChooseHolder(
                        LayoutInflater.from(this.context).inflate(R.layout.cell_setting_choose, parent, false));
            case 2:
            case 3:
            case 4:
                return new InputHolder(
                        LayoutInflater.from(this.context).inflate(R.layout.cell_setting_input, parent, false));
            case -1:
                return new DividerHolder(
                        LayoutInflater.from(this.context).inflate(R.layout.cell_divider, parent, false));
            case -2:
                return new TitleHolder(
                        LayoutInflater.from(this.context).inflate(R.layout.cell_setting_title, parent, false));
            default:
                return new SwitchHolder(
                        LayoutInflater.from(this.context).inflate(R.layout.cell_setting_switch, parent, false));
        }
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (position < 0 || position >= list.size())
            return;
        SettingSection settingSection = list.get(position);
        if (settingSection == null)
            return;

        switch (holder.getItemViewType()) {
            case -1:
                break;
            case -2:
                TitleHolder titleHolder = (TitleHolder) holder;
                titleHolder.bind(settingSection);
                break;
            case 1:
                ChooseHolder chooseHolder = (ChooseHolder) holder;
                chooseHolder.bind(settingSection);
                break;
            case 2:
            case 3:
            case 4:
                InputHolder inputHolder = (InputHolder) holder;
                inputHolder.bind(settingSection);
                break;
            default:
                SwitchHolder switchHolder = (SwitchHolder) holder;
                switchHolder.bind(settingSection);
                break;
        }
    }

    @Override
    public int getItemCount() {
        return list != null ? list.size() : 0;
    }

    public static class SwitchHolder extends RecyclerView.ViewHolder {
        final TextView desc;
        final SwitchMaterial switchMaterial;

        public SwitchHolder(@NonNull View itemView) {
            super(itemView);
            desc = itemView.findViewById(R.id.setting_switch_desc);
            switchMaterial = itemView.findViewById(R.id.setting_switch);
        }

        public void bind(SettingSection settingSection) {
            if (settingSection.desc == null || settingSection.desc.isEmpty())
                desc.setVisibility(View.GONE);
            else {
                desc.setText(settingSection.desc);
                desc.setVisibility(View.VISIBLE);
            }
            switchMaterial.setText(settingSection.name);
            final String[] warn = warnMessages(settingSection);
            switchMaterial.setOnCheckedChangeListener(null);
            switchMaterial.setChecked(SharedPreferencesUtil.getBoolean(settingSection.id,
                    Boolean.parseBoolean(settingSection.defaultValue)));
            switchMaterial.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (warn == null) {
                    SharedPreferencesUtil.putBoolean(settingSection.id, isChecked);
                    return;
                }
                showWarnConfirm(settingSection, isChecked, isChecked ? warn[0] : warn[1]);
            });
        }

        /**
         * 「带警告的开关」（type = {@code switch_warn}）：切换前先弹确认，用户取消则不落库。
         *
         * <p>提示文案借 {@link SettingSection#extra} 携带：{@code [0]=打开时的警告}、
         * {@code [1]=关闭时的警告}——两者语义不同（打开是"别乱开"，关闭是"会删掉本地日志"），
         * 不能只给一句通用文案。
         */
        private static String[] warnMessages(SettingSection settingSection) {
            if (!"switch_warn".equals(settingSection.type)) return null;
            if (!(settingSection.extra instanceof String[])) return null;
            String[] arr = (String[]) settingSection.extra;
            return arr.length >= 2 ? arr : null;
        }

        private void showWarnConfirm(SettingSection settingSection, boolean target, String message) {
            new AlertDialog.Builder(itemView.getContext())
                    .setTitle(settingSection.name)
                    .setMessage(message == null ? "" : message)
                    .setPositiveButton("确定", (dialog, which) ->
                            SharedPreferencesUtil.putBoolean(settingSection.id, target))
                    .setNegativeButton("取消", null)
                    .setOnDismissListener(dialog -> {
                        boolean stored = SharedPreferencesUtil.getBoolean(settingSection.id,
                                Boolean.parseBoolean(settingSection.defaultValue));
                        //取消（或返回/点外部关闭）时数据没变，按数据重新渲染一遍把开关拨回去；
                        //走 bind 而不是直接 setChecked，是为了避免重复维护"摘监听→拨回→装监听"这套顺序
                        if (stored != target) bind(settingSection);
                    })
                    .show();
        }
    }

    public static class ChooseHolder extends RecyclerView.ViewHolder {
        final RadioButton chocola;
        final RadioButton vanilla; // 我在思考这样的命名方式是否合理（玩艹猫玩的
        final TextView name;
        final TextView desc;

        public ChooseHolder(@NonNull View itemView) {
            super(itemView);
            chocola = itemView.findViewById(R.id.setting_choose_chocola);
            vanilla = itemView.findViewById(R.id.setting_choose_vanilla);
            desc = itemView.findViewById(R.id.setting_choose_desc);
            name = itemView.findViewById(R.id.setting_choose_name);
        }

        public void bind(SettingSection settingSection) {
            if (settingSection.desc == null || settingSection.desc.isEmpty())
                desc.setVisibility(View.GONE);
            else {
                desc.setText(settingSection.desc);
                desc.setVisibility(View.VISIBLE);
            }
            name.setText(settingSection.name);

            //动态按钮的清理必须在**每次** bind 的最前面做，对所有选项数都生效。
            //
            //这里曾有过一个 P0：清理原先只在多选项路径（bindMulti）里做，而两选项与多选项
            //共用同一个 holder（typeMap 里都是 0/1，落到同一个 ChooseHolder）。滚动复用时
            //holder 会从"5 选项项"被拿去绑"2 选项项"，那条路径不清理 → 残留的
            //MaterialRadioButton 带着上一次的文案与勾选，**闭包里捕获的还是上一次的
            //settingSection**，点一下就会 putInt(旧 key, 旧 index)，静默改写另一个设置项。
            //放在早退之前：extra 异常（null/空）时也要清干净，否则同样会留下残留按钮。
            clearDynamicOptions();

            String[] strings = (String[]) settingSection.extra;
            if (strings == null || strings.length == 0) return;

            if (strings.length > 2) {
                bindMulti(settingSection, strings);
                return;
            }

            //两选项沿用历史的 boolean 存储（chocola=true / vanilla=false）：
            //既有设置项（显示方式/解码方式/音频输出）已按 boolean 存过值，改语义会把它们读错
            chocola.setText(strings[0]);
            vanilla.setText(strings[1]);

            boolean value = SharedPreferencesUtil.getBoolean(settingSection.id,
                    Boolean.parseBoolean(settingSection.defaultValue));
            chocola.setOnCheckedChangeListener(null);
            vanilla.setOnCheckedChangeListener(null);
            chocola.setChecked(value);
            vanilla.setChecked(!value);

            chocola.setOnCheckedChangeListener(
                    (buttonView, isChecked) -> SharedPreferencesUtil.putBoolean(settingSection.id, isChecked)); // 有些选项的true和false不能改了，所以交换
            vanilla.setOnCheckedChangeListener(
                    (buttonView, isChecked) -> SharedPreferencesUtil.putBoolean(settingSection.id, !isChecked));
        }

        /**
         * 清掉上一次 bind 动态加进来的选项按钮，布局里静态的那两个（chocola/vanilla）保留。
         *
         * <p>不能整体 removeAllViews：静态按钮是这个 holder 唯一的承载，被摘掉之后
         * 再用于两选项的项时就再也显示不出来了。
         */
        private void clearDynamicOptions() {
            //getParent() 返回的是 ViewParent 接口，不能直接当成 View 用
            if (!(chocola.getParent() instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) chocola.getParent();
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (child != chocola && child != vanilla) group.removeViewAt(i);
            }
        }

        /**
         * 三个及以上选项：用 int 存**选项下标**（{@code defaultValue} 即默认下标）。
         *
         * <p>与两选项的 boolean 路径完全独立，各自 key 不重叠，因此互不影响。
         * 读侧必须同样按下标解读（见 {@code SegmentSkipMode.fromIndex}）——枚举成员声明顺序
         * 一旦变动，下标语义就变，不能按枚举里的 value 字段反查。
         */
        private void bindMulti(SettingSection settingSection, String[] strings) {
            ViewGroup group = (ViewGroup) chocola.getParent();
            //动态按钮已由 bind() 统一清理（含两选项与 extra 异常两条路径），此处不再重复

            int current;
            try {
                current = Integer.parseInt(settingSection.defaultValue);
            } catch (NumberFormatException e) {
                current = 0;
            }
            current = SharedPreferencesUtil.getInt(settingSection.id, current);
            if (current < 0 || current >= strings.length) current = 0;

            chocola.setOnCheckedChangeListener(null);
            vanilla.setOnCheckedChangeListener(null);
            chocola.setText(strings[0]);
            vanilla.setText(strings[1]);
            chocola.setChecked(current == 0);
            vanilla.setChecked(current == 1);

            chocola.setOnCheckedChangeListener((v, checked) -> {
                if (checked) SharedPreferencesUtil.putInt(settingSection.id, 0);
            });
            vanilla.setOnCheckedChangeListener((v, checked) -> {
                if (checked) SharedPreferencesUtil.putInt(settingSection.id, 1);
            });

            for (int i = 2; i < strings.length; i++) {
                MaterialRadioButton btn = new MaterialRadioButton(group.getContext());
                btn.setText(strings[i]);
                btn.setTextColor(desc.getCurrentTextColor());
                btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, chocola.getTextSize());
                btn.setChecked(i == current);
                final int index = i;
                btn.setOnCheckedChangeListener((v, checked) -> {
                    if (checked) SharedPreferencesUtil.putInt(settingSection.id, index);
                });
                group.addView(btn);
            }
        }
    }

    public static class InputHolder extends RecyclerView.ViewHolder {
        final EditText input;
        final TextView name;
        final TextView desc;
        SettingSection bound; //当前绑定的设置项；重绑 setText 期间置空，防止把本项值串写进其他项的 key

        public InputHolder(@NonNull View itemView) {
            super(itemView);
            input = itemView.findViewById(R.id.setting_input_edittext);
            desc = itemView.findViewById(R.id.setting_input_desc);
            name = itemView.findViewById(R.id.setting_input_name);
            input.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                }

                @Override
                public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                }

                @Override
                public void afterTextChanged(Editable editable) {
                    SettingSection settingSection = bound;
                    if (settingSection == null)
                        return;
                    try {
                        switch (settingSection.type) {
                            case "input_int":
                                SharedPreferencesUtil.putInt(settingSection.id,
                                        Integer.parseInt(editable.toString()));
                                break;
                            case "input_float":
                                SharedPreferencesUtil.putFloat(settingSection.id,
                                        Float.parseFloat(editable.toString()));
                                break;
                            default:
                                SharedPreferencesUtil.putString(settingSection.id, editable.toString());
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        public void bind(SettingSection settingSection) {
            if (settingSection.desc == null || settingSection.desc.isEmpty())
                desc.setVisibility(View.GONE);
            else {
                desc.setText(settingSection.desc);
                desc.setVisibility(View.VISIBLE);
            }
            name.setText(settingSection.name);
            bound = null;
            switch (settingSection.type) {
                case "input_int":
                    int intValue = SharedPreferencesUtil.getInt(settingSection.id,
                            Integer.parseInt(settingSection.defaultValue));
                    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
                    input.setText(String.valueOf(intValue));
                    break;
                case "input_float":
                    float floatValue = SharedPreferencesUtil.getFloat(settingSection.id,
                            Float.parseFloat(settingSection.defaultValue));
                    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                    input.setText(String.valueOf(floatValue));
                    break;
                default:
                    String strValue = SharedPreferencesUtil.getString(settingSection.id, settingSection.defaultValue);
                    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                    input.setText(strValue);
            }
            bound = settingSection;
        }
    }

    public static class DividerHolder extends RecyclerView.ViewHolder {

        public DividerHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    public static class TitleHolder extends RecyclerView.ViewHolder {
        final TextView titleText;

        public TitleHolder(@NonNull View itemView) {
            super(itemView);
            titleText = itemView.findViewById(R.id.setting_title_text);
        }

        public void bind(SettingSection settingSection) {
            titleText.setText(settingSection.name);
        }
    }
}
