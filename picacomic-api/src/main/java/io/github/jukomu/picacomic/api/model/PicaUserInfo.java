package io.github.jukomu.picacomic.api.model;

import java.util.List;

/**
 * @author JUKOMU
 * @Description: 代表登录成功后返回的用户详细信息
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/20
 */
public record PicaUserInfo(
        String id,
        String name,
        String email,
        String slogan,
        String birthday, // 格式: 2003-09-25T00:00:00.000Z
        String gender, // m, f, bot
        String title,
        boolean verified,
        int exp,
        int level,
        List<String> characters,
        String createdAt,
        PicaAvatar avatar,
        boolean isPunched
) {
    /**
     * 创建一个仅包含用户名的部分填充的 PicaUserInfo 对象。
     */
    public static PicaUserInfo partial(String name) {
        return new PicaUserInfo(null, name, null, null, null, null, null, false, 0, 1, null, null, null, false);
    }

    /**
     * 获取用户ID
     *
     * @return 用户ID
     */
    public String getId() {
        return id;
    }

    /**
     * 获取用户名
     *
     * @return 用户名
     */
    public String getName() {
        return name;
    }

    /**
     * 获取邮箱
     *
     * @return 邮箱
     */
    public String getEmail() {
        return email;
    }

    /**
     * 获取签名
     *
     * @return 签名
     */
    public String getSlogan() {
        return slogan;
    }

    /**
     * 获取生日
     *
     * @return 生日
     */
    public String getBirthday() {
        return birthday;
    }

    /**
     * 获取性别
     *
     * @return 性别
     */
    public String getGender() {
        return gender;
    }

    /**
     * 获取称号
     *
     * @return 称号
     */
    public String getTitle() {
        return title;
    }

    /**
     * 是否认证
     *
     * @return 是否认证
     */
    public boolean isVerified() {
        return verified;
    }

    /**
     * 获取经验值
     *
     * @return 经验值
     */
    public int getExp() {
        return exp;
    }

    /**
     * 获取等级
     *
     * @return 等级
     */
    public int getLevel() {
        return level;
    }

    /**
     * 获取角色
     *
     * @return 角色
     */
    public List<String> getCharacters() {
        return characters;
    }

    /**
     * 获取创建日期
     *
     * @return 创建日期
     */
    public String getCreatedAt() {
        return createdAt;
    }

    /**
     * 获取头像
     *
     * @return 头像
     */
    public PicaAvatar getAvatar() {
        return avatar;
    }

    /**
     * 是否已签到
     *
     * @return 是否已签到
     */
    public boolean isPunched() {
        return isPunched;
    }
}
