package io.github.jukomu.picacomic.api.client;

/**
 * 一个图片请求的最小生命周期句柄。
 *
 * <p>每个句柄只允许执行一次。{@link #execute()} 是阻塞调用；实现不会因为创建句柄
 * 而偷偷切换到库自己的线程。调用 {@link #cancel()} 和 {@link #close()} 都是线程安全、
 * 幂等操作。</p>
 */
public interface PicaImageRequest extends AutoCloseable {

    /**
     * 在调用线程同步执行图片请求。
     *
     * @return 完整且通过边界校验的图片 bytes
     */
    byte[] execute();

    /**
     * 取消尚未完成的请求。
     */
    void cancel();

    /**
     * 判断请求是否因显式取消或所属 client 关闭而终止。
     *
     * @return 是否已取消
     */
    boolean isCancelled();

    /**
     * 关闭句柄并释放其仍持有的资源。
     */
    @Override
    void close();
}
