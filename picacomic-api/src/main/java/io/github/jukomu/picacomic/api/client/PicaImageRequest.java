package io.github.jukomu.picacomic.api.client;

/**
 * 一个图片请求的最小生命周期句柄。
 *
 * <p>每个句柄只允许执行一次。{@link #execute()} 是阻塞调用；实现不会因为创建句柄
 * 而偷偷切换到库自己的线程。调用 {@link #cancel()} 和 {@link #close()} 都是线程安全、
 * 幂等操作。</p>
 */
public interface PicaImageRequest extends PicaRequest<byte[]> {
}
