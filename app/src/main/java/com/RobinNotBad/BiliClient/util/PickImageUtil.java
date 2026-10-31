package com.RobinNotBad.BiliClient.util;

import android.content.ActivityNotFoundException;

import androidx.activity.result.ActivityResultLauncher;

/**
 * 相册选图的唯一入口。
 *
 * <p>为什么要包一层：{@code ActivityResultContracts.GetContent / GetMultipleContents} 内部走
 * {@code ACTION_GET_CONTENT + CATEGORY_OPENABLE}，精简 ROM 与手表（如 GLL-AL00、OWW 系）上
 * 没有任何 Activity 能处理这个 Intent 时，{@code launch()} 会<b>直接抛</b>
 * ActivityNotFoundException，不会转成 result 回调。1.2.5 期间因此崩了 5 次
 * （写回复 3 次、私信发图 2 次）。
 *
 * <p>不在这里做 {@code resolveActivity} 探活：契约真正发出的 Intent 会再补 CATEGORY 与 EXTRA，
 * 与手上拼的那份不完全一致，探活通过也可能照样抛。直接接异常是 {@code SettingUIActivity}
 * 里已经验证过的做法（那里注释记录过线上 3 例）。
 */
public final class PickImageUtil {

    private PickImageUtil() {
    }

    /**
     * 拉起系统选图，失败时提示用户而不是崩溃。
     *
     * <p>只用于 {@code String} 输入的契约（GetContent / GetMultipleContents）。
     */
    public static void launchSafe(ActivityResultLauncher<String> launcher) {
        if (launcher == null) return;
        try {
            launcher.launch("image/*");
        } catch (ActivityNotFoundException e) {
            CrashTrace.log("选图: 无可用文件选择器 " + e.getClass().getSimpleName());
            MsgUtil.showMsg("此设备没有可用的文件选择器\n无法选择图片");
        } catch (IllegalStateException e) {
            //页面已经不在（launcher 已注销）：提示没有意义，只留痕
            CrashTrace.log("选图: launcher 已失效 " + e.getClass().getSimpleName());
        } catch (Exception e) {
            CrashTrace.log("选图: 拉起失败 " + e.getClass().getSimpleName());
            MsgUtil.showMsg("打开图片选择器失败");
        }
    }
}
