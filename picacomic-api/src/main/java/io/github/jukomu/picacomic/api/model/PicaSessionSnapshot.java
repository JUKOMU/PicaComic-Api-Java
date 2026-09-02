package io.github.jukomu.picacomic.api.model;

import io.github.jukomu.picacomic.api.enums.PicaSessionState;

import java.util.Objects;

/**
 * 当前 client 的不含凭据的进程内会话快照。
 *
 * <p>只有 {@link PicaSessionState#SIGNED_IN} 或 {@link PicaSessionState#EXPIRED}
 * 状态会包含用户信息。client 为每次公开快照递归复制用户对象中的嵌套集合。</p>
 */
public record PicaSessionSnapshot(
        PicaSessionState state,
        PicaUserInfo user
) {
    /**
     * 校验会话状态与公开用户信息是否匹配。
     */
    public PicaSessionSnapshot {
        Objects.requireNonNull(state, "Session state cannot be null");
        if ((state == PicaSessionState.SIGNED_IN || state == PicaSessionState.EXPIRED)
                && user == null) {
            throw new IllegalArgumentException("Signed-in session states require a user");
        }
        if ((state == PicaSessionState.SIGNED_OUT || state == PicaSessionState.AUTHENTICATING)
                && user != null) {
            throw new IllegalArgumentException("This session state cannot expose a user");
        }
    }

    /**
     * 获取会话状态。
     *
     * @return 当前会话状态
     */
    public PicaSessionState getState() {
        return state;
    }

    /**
     * 获取当前会话公开的用户信息副本。
     *
     * @return 用户信息；未登录或正在认证时为 {@code null}
     */
    public PicaUserInfo getUser() {
        return user;
    }
}
