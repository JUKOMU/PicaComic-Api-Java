package io.github.jukomu.picacomic.api.client;

/**
 * 一个逻辑 API 操作的同步、单次使用句柄。
 *
 * <p>创建句柄不会启动库自有的工作线程。{@link #execute()} 在调用线程执行操作，
 * 其他线程可以调用 {@link #cancel()} 取消当前操作及其后续子请求。</p>
 *
 * @param <T> 操作结果类型
 */
public interface PicaRequest<T> extends AutoCloseable {

    /**
     * 在调用线程执行一次操作。
     *
     * @return 操作结果
     */
    T execute();

    /**
     * 取消此操作。该方法线程安全且幂等。
     */
    void cancel();

    /**
     * 判断句柄是否已被调用方或所属 client 取消。
     *
     * @return 是否已请求取消
     */
    boolean isCancelled();

    /**
     * 取消未完成的工作并释放句柄持有的状态。
     */
    @Override
    void close();
}
