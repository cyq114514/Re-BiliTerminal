package com.RobinNotBad.BiliClient.api;

/**
 * Cookie 刷新结果。
 *
 * <p>替代原来的 boolean 返回值，目的是让调用方能区分「瞬时失败」与「服务端判定失效」：
 * 前者必须保留本地凭证等待重试，后者才应该清理登录态。
 * 原实现把两者一律当成失败并清空全部凭证（含 refresh_token），
 * 导致一次网络抖动就等于把用户登出，且丢掉了唯一能自救的凭据。
 *
 * <p>本枚举不携带任何凭证内容。
 */
public enum CookieRefreshResult {
    /** 刷新成功 */
    OK,

    /** 本地缺少续期凭据（refresh_token 为空），无法发起刷新 */
    NO_REFRESH_TOKEN,

    /** 校验口令 refresh_csrf 未取到（对应页面解析失败等），属瞬时故障 */
    NO_REFRESH_CSRF,

    /**
     * 服务端明确否定了凭证本身（如 -101 账号未登录、-111 csrf 校验失败）。
     * 续期链路已断，只有这种情况才应清理登录态。
     */
    REFRESH_REJECTED,

    /**
     * 刷新请求被服务端以风控/限流/其他非 0 码挡下（如 -412 风控、-509 频率限制、服务端内部错误）。
     * 凭证本身可能仍然有效，保留待重试，不当作失效处理。
     */
    REFRESH_RETRYABLE,

    /** 确认更新失败：本地 Cookie 已回滚到刷新前的串，旧凭证仍可能有效，保留待重试 */
    CONFIRM_FAILED,

    /** 网络层异常（超时/断网），属瞬时失败 */
    NETWORK_ERROR;

    /**
     * 是否为「可重试」结果。除成功、明确失效、缺少凭据之外，其余都应按可重试处理。
     */
    public boolean isRetryable() {
        return this == NO_REFRESH_CSRF || this == REFRESH_RETRYABLE
                || this == CONFIRM_FAILED || this == NETWORK_ERROR;
    }

    /** 是否需要清理本地登录态（唯一条件：服务端明确判定凭证失效） */
    public boolean requiresRelogin() {
        return this == REFRESH_REJECTED;
    }
}
