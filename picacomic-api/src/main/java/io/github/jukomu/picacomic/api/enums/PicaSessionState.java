package io.github.jukomu.picacomic.api.enums;

/**
 * 一个 Pica client 的进程内认证状态。
 */
public enum PicaSessionState {
    /** 尚未登录，client 不持有当前会话凭据。 */
    SIGNED_OUT,
    /** 正在执行登录流程，认证结果尚未提交。 */
    AUTHENTICATING,
    /** 登录流程已完成并提交了当前用户会话。 */
    SIGNED_IN,
    /** 当前会话因服务端拒绝认证而失效。 */
    EXPIRED
}
