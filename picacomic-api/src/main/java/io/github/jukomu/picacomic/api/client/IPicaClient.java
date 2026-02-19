package io.github.jukomu.picacomic.api.client;

import io.github.jukomu.picacomic.api.model.PicaUserInfo;

/**
 * @author JUKOMU
 * @Description: PicaComic-Api-Java 的核心客户端公开接口，
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public interface IPicaClient {

    // == 核心数据获取层 ==

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
