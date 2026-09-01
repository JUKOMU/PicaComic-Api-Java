package io.github.jukomu.picacomic.core;

@FunctionalInterface
interface DomainProbe {

    boolean isReachable(String domain);
}
