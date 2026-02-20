package io.github.jukomu.picacomic.api.client;

import io.github.jukomu.picacomic.api.model.*;

/**
 * @author JUKOMU
 * @Description: PicaComic-Api-Java 的核心客户端公开接口，
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public interface IPicaClient {

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
     * 获取一张图片的二进制数据
     *
     * @param image 图片信息
     * @return 图片的二进制数据
     */
    byte[] fetchImageBytes(PicaImage image);

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
}
