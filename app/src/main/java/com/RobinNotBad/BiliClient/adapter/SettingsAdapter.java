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
import androidx.recyclerview.widget.RecyclerView;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.model.SettingSection;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
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
            switchMaterial.setOnCheckedChangeListener(null);
            switchMaterial.setChecked(SharedPreferencesUtil.getBoolean(settingSection.id,
                    Boolean.parseBoolean(settingSection.defaultValue)));
            switchMaterial.setOnCheckedChangeListener(
                    (buttonView, isChecked) -> SharedPreferencesUtil.putBoolean(settingSection.id, isChecked));
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
            String[] strings = (String[]) settingSection.extra;
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
