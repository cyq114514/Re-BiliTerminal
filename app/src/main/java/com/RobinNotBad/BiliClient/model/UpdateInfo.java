package com.RobinNotBad.BiliClient.model;

/**
 * 应用内更新检查的结果模型（数据来自 GitHub Releases）。
 * 更新判断以 versionName 的语义化比较为准（见 {@link com.RobinNotBad.BiliClient.util.VersionNameUtil}），
 * versionCode 仅作解析失败时的兜底与展示；versionName 来自 release 说明里的元数据注释（CI 追加）
 * 或 tag 名，versionCode 为 0 不影响判断。
 */
public class UpdateInfo {
    public long versionCode = 0;
    public String tagName = "";       //如 v1.1.3
    public String versionName = "";   //tagName 去掉 v 前缀
    public String releaseUrl = "";    //release 页面地址
    public String notes = "";         //release 说明（即更新日志）
    public String apkUrl = "";        //APK 资产下载地址（unsigned 包会被过滤）
    public String sha256 = null;      //安装包哈希（可选，用于下载校验）
    public long size = -1;            //安装包期望大小（字节；sha256 缺失时的完整性校验兜底）

    /**是否携带了足够的自动更新信息（版本名 + 可安装的 APK 资产）。*/
    public boolean isUsable() {
        return versionName != null && !versionName.isEmpty()
                && apkUrl != null && !apkUrl.isEmpty();
    }
}
