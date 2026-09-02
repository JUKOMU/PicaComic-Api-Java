package io.github.jukomu.picacomic.api.enums;

/**
 * The in-process authentication state of one Pica client.
 */
public enum PicaSessionState {
    SIGNED_OUT,
    AUTHENTICATING,
    SIGNED_IN,
    EXPIRED
}
