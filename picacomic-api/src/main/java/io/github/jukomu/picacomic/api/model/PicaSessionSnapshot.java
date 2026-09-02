package io.github.jukomu.picacomic.api.model;

import io.github.jukomu.picacomic.api.enums.PicaSessionState;

import java.util.Objects;

/**
 * A credential-free view of the current client session.
 *
 * <p>The user value is present only while signed in or expired. Its nested
 * collections are copied by the client for every public snapshot.</p>
 */
public record PicaSessionSnapshot(
        PicaSessionState state,
        PicaUserInfo user
) {
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

    public PicaSessionState getState() {
        return state;
    }

    public PicaUserInfo getUser() {
        return user;
    }
}
