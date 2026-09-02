package io.github.jukomu.picacomic.api.client;

import io.github.jukomu.picacomic.api.enums.TimeOption;
import io.github.jukomu.picacomic.api.model.*;
import io.github.jukomu.picacomic.api.strategy.IAlbumPathGenerator;
import io.github.jukomu.picacomic.api.strategy.IImagePathGenerator;
import io.github.jukomu.picacomic.api.strategy.IPhotoPathGenerator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * @author JUKOMU
 * @Description: PicaComic-Api-Java 的核心客户端公开接口，
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public interface IPicaClient extends AutoCloseable {

    /**
     * 关闭 client 自有网络资源；外部注入的 executor 仍由调用者负责。
     */
    @Override
    void close();

    // == 核心数据获取层 ==

    /**
     * 根据本子id获取本子详情
     *
     * @param albumId 本子id
     * @return 本子详情对象
     */
    PicaAlbum getAlbum(String albumId);

    /**
     * 根据章节id获取章节详情
     *
     * @param albumId 本子id
     * @param order   顺序
     * @return 章节详情对象
     */
    PicaPhoto getPhoto(String albumId, int order);

    /**
     * 根据稳定章节 ID 获取章节详情；该路径使用章节 ID 作为缓存身份。
     *
     * @param albumId   本子 ID
     * @param chapterId 稳定章节 ID
     * @return 章节详情对象
     */
    PicaPhoto getPhoto(String albumId, String chapterId);

    /**
     * 绕过章节缓存并按稳定章节 ID 刷新章节详情。
     */
    PicaPhoto refreshPhoto(String albumId, String chapterId);

    /**
     * 绕过缓存刷新本子及其章节索引。
     */
    PicaAlbum refreshAlbum(String albumId);

    /**
     * 本地使本子及其章节缓存失效；下一次读取会访问网络。
     */
    void invalidateAlbum(String albumId);

    /**
     * 获取一张图片的二进制数据
     *
     * @param image 图片信息
     * @return 图片的二进制数据
     */
    default byte[] fetchImageBytes(PicaImage image) {
        try (PicaImageRequest request = newImageRequest(image)) {
            return request.execute();
        }
    }

    /**
     * 创建一个可取消的同步图片请求句柄。
     *
     * <p>句柄本身不会提交任务。调用 {@link PicaImageRequest#execute()} 时在调用线程
     * 执行，调用者可以从自己的线程池中调用它并通过 {@link PicaImageRequest#cancel()}
     * 取消当前请求。</p>
     *
     * @param image 图片信息
     * @return 单次图片请求句柄
     */
    PicaImageRequest newImageRequest(PicaImage image);

    PicaRequest<PicaAlbum> newAlbumRequest(String albumId);

    PicaRequest<PicaAlbum> newAlbumRefreshRequest(String albumId);

    PicaRequest<PicaPhoto> newPhotoRequest(String albumId, String chapterId);

    PicaRequest<PicaPhoto> newPhotoRefreshRequest(String albumId, String chapterId);

    PicaRequest<PicaPhoto> newPhotoByOrderRequest(String albumId, int order);

    PicaRequest<PicaContentPage> newSearchRequest(SearchQuery query);

    PicaRequest<PicaContentPage> newFavoritesRequest(SearchQuery query);

    PicaRequest<PicaContentPage> newCategoriesRequest(SearchQuery query);

    PicaRequest<PicaContentPage> newLeaderboardRequest(TimeOption timeOption);

    PicaRequest<List<PicaUserInfo>> newKnightLeaderboardRequest();

    PicaRequest<PicaContentPage> newRandomAlbumsRequest();

    PicaRequest<PicaUserInfo> newUserInfoRequest();

    PicaRequest<PicaUserInfo> newLoginRequest(String userNameOrEmail, String password);

    /**
     * 搜索本子
     *
     * @param query 搜索的参数
     * @return 搜索页的一页结果
     */
    PicaContentPage search(SearchQuery query);

    /**
     * 获取收藏夹
     *
     * @param query 收藏夹的参数
     * @return 收藏夹的一页结果
     */
    PicaContentPage getFavorites(SearchQuery query);

    /**
     * 获取分类排行
     *
     * @param query 分类的参数
     * @return 分类列表页的一页结果
     */
    PicaContentPage getCategories(SearchQuery query);

    /**
     * 获取排行榜
     *
     * @param timeOption 时间范围
     * @return 排行榜
     */
    PicaContentPage getLeaderboard(TimeOption timeOption);

    /**
     * 获取骑士榜
     *
     * @return 骑士榜
     */
    List<PicaUserInfo> getKnightLeaderboard();

    /**
     * 获取一组随机本子
     *
     * @return 一组随机本子
     */
    PicaContentPage getRandomAlbums();

    /**
     * 获取用户信息
     *
     * @return 用户信息对象
     */
    PicaUserInfo getUserInfo();

    // == 会话管理层 ==

    /**
     * 登录
     *
     * @param userNameOrEmail 用户名或邮箱
     * @param password        密码
     * @return 用户信息对象
     */
    PicaUserInfo login(String userNameOrEmail, String password);

    /**
     * 获取不含 token、cookie 或密码的当前进程会话快照。
     */
    PicaSessionSnapshot getSession();

    /**
     * 清除当前 client 的本地会话并取消该会话的 API 请求。
     */
    void logout();

    // == 便利操作层 ==

    /**
     * 下载图片到默认路径
     *
     * @param image 图片信息
     */
    void downloadImage(PicaImage image) throws IOException;

    /**
     * 下载图片
     *
     * @param imageUrl 图片URL
     */
    void downloadImage(String imageUrl, Path path) throws IOException;

    /**
     * 下载图片到指定路径
     *
     * @param image              图片信息
     * @param imagePathGenerator 路径
     */
    void downloadImage(PicaImage image, IImagePathGenerator imagePathGenerator) throws IOException;

    /**
     * 下载图片到指定路径
     *
     * @param image 图片信息
     * @param path  路径
     */
    void downloadImage(PicaImage image, Path path) throws IOException;

    /**
     * 下载章节到默认路径
     *
     * @param photo 章节详情对象
     * @return 下载结果报告
     */
    DownloadResult downloadPhoto(PicaPhoto photo);

    /**
     * 下载章节到指定路径
     *
     * @param photo         章节详情对象
     * @param pathGenerator 路径
     * @return 下载结果报告
     */
    DownloadResult downloadPhoto(PicaPhoto photo, IPhotoPathGenerator pathGenerator);

    /**
     * 下载章节到指定路径
     *
     * @param photo 章节详情对象
     * @param path  路径
     * @return 下载结果报告
     */
    DownloadResult downloadPhoto(PicaPhoto photo, Path path);

    /**
     * 下载章节到指定路径
     *
     * @param photo         章节详情对象
     * @param pathGenerator 路径
     * @param executor      线程池
     * @return 下载结果报告
     */
    DownloadResult downloadPhoto(PicaPhoto photo, IPhotoPathGenerator pathGenerator, ExecutorService executor);

    /**
     * 下载章节到指定路径
     *
     * @param photo    章节详情对象
     * @param path     路径
     * @param executor 线程池
     * @return 下载结果报告
     */
    DownloadResult downloadPhoto(PicaPhoto photo, Path path, ExecutorService executor);

    /**
     * 下载本子到指定路径
     *
     * @param album 本子详情对象
     * @return 下载结果报告
     */
    DownloadResult downloadAlbum(PicaAlbum album);

    /**
     * 下载本子到指定路径
     *
     * @param album         本子详情对象
     * @param pathGenerator 路径
     * @return 下载结果报告
     */
    DownloadResult downloadAlbum(PicaAlbum album, IAlbumPathGenerator pathGenerator);

    /**
     * 下载本子到指定路径
     *
     * @param album 本子详情对象
     * @param path  路径
     * @return 下载结果报告
     */
    DownloadResult downloadAlbum(PicaAlbum album, Path path);

    /**
     * 下载本子到指定路径
     *
     * @param album         本子详情对象
     * @param pathGenerator 路径
     * @param executor      线程池
     * @return 下载结果报告
     */
    DownloadResult downloadAlbum(PicaAlbum album, IAlbumPathGenerator pathGenerator, ExecutorService executor);

    /**
     * 下载本子到指定路径
     *
     * @param album    本子详情对象
     * @param path     路径
     * @param executor 线程池
     * @return 下载结果报告
     */
    DownloadResult downloadAlbum(PicaAlbum album, Path path, ExecutorService executor);

}
