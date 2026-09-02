package io.github.jukomu.picacomic.api.model;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;

/**
 * @author JUKOMU
 * @Description: 本子详情详细信息
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/20
 */
public record PicaAlbum(
        String id,
        PicaUserInfo creator,
        String title,
        String description,
        PicaImage thumb,
        String author,
        String chineseTeam,
        List<String> categories,
        List<String> tags,
        int pagesCount,
        int epsCount,
        boolean finished,
        String updatedAt,
        String createdAt,
        boolean allowDownload,
        boolean allowComment,
        int totalLikes,
        int totalViews,
        int totalComments,
        int viewsCount,
        int likesCount,
        int commentsCount,
        boolean isFavourite,
        boolean isLiked,
        List<PicaPhoto> photos
) {
    /**
     * 获取本子 ID (对应 _id)
     *
     * @return 本子唯一标识符
     */
    public String getId() {
        return id;
    }

    /**
     * 获取上传者/创作者信息 (对应 _creator)
     *
     * @return 创作者对象
     */
    public PicaUserInfo getCreator() {
        return creator;
    }

    /**
     * 获取本子标题
     *
     * @return 标题
     */
    public String getTitle() {
        return title;
    }

    /**
     * 获取本子简介
     *
     * @return 简介文本
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取封面图片
     *
     * @return 封面对象
     */
    public PicaImage getThumb() {
        return thumb;
    }

    /**
     * 获取作者
     *
     * @return 作者名
     */
    public String getAuthor() {
        return author;
    }

    /**
     * 获取汉化组信息
     *
     * @return 汉化组名称
     */
    public String getChineseTeam() {
        return chineseTeam;
    }

    /**
     * 获取分类列表
     *
     * @return 分类字符串列表
     */
    public List<String> getCategories() {
        return categories;
    }

    /**
     * 获取标签列表
     *
     * @return 标签字符串列表
     */
    public List<String> getTags() {
        return tags;
    }

    /**
     * 获取总页数
     *
     * @return 图片页数
     */
    public int getPagesCount() {
        return pagesCount;
    }

    /**
     * 获取总章节数
     *
     * @return 章节数量
     */
    public int getEpsCount() {
        return epsCount;
    }

    /**
     * 获取完结状态
     *
     * @return true 为已完结
     */
    public boolean isFinished() {
        return finished;
    }

    /**
     * 获取更新时间
     *
     * @return ISO 8601 格式的时间字符串
     */
    public String getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 获取创建时间
     *
     * @return ISO 8601 格式的时间字符串
     */
    public String getCreatedAt() {
        return createdAt;
    }

    /**
     * 是否允许下载
     *
     * @return true 为允许
     */
    public boolean isAllowDownload() {
        return allowDownload;
    }

    /**
     * 是否允许评论
     *
     * @return true 为允许
     */
    public boolean isAllowComment() {
        return allowComment;
    }

    /**
     * 获取总点赞数
     *
     * @return 点赞总数
     */
    public int getTotalLikes() {
        return Math.max(totalLikes, likesCount);
    }

    /**
     * 获取总浏览数
     *
     * @return 浏览总数
     */
    public int getTotalViews() {
        return Math.max(totalViews, viewsCount);
    }

    /**
     * 获取总评论数
     *
     * @return 评论总数
     */
    public int getTotalComments() {
        return Math.max(totalComments, commentsCount);
    }

    /**
     * 获取浏览数 (同 totalViews)
     *
     * @return 浏览数
     */
    public int getViewsCount() {
        return Math.max(totalViews, viewsCount);
    }

    /**
     * 获取点赞数 (同 totalLikes)
     *
     * @return 点赞数
     */
    public int getLikesCount() {
        return Math.max(totalLikes, likesCount);
    }

    /**
     * 获取评论数 (同 totalComments)
     *
     * @return 评论数
     */
    public int getCommentsCount() {
        return Math.max(totalComments, commentsCount);
    }

    /**
     * 当前用户是否已收藏
     *
     * @return true 为已收藏
     */
    public boolean isFavourite() {
        return isFavourite;
    }

    /**
     * 当前用户是否已点赞
     *
     * @return true 为已点赞
     */
    public boolean isLiked() {
        return isLiked;
    }

    /**
     * 获取章节列表
     *
     * @return 章节列表
     */
    public List<PicaPhoto> getPhotos() {
        return photos;
    }

    /**
     * 根据章节id获取章节
     *
     * @param photoId 章节id
     * @return 章节
     */
    public PicaPhoto getPhoto(String photoId) {
        for (PicaPhoto photo : photos) {
            String id = photo.getId();
            if (photoId.equals(id)) {
                return photo;
            }
        }
        return null;
    }

    /**
     * 根据顺序获取章节
     *
     * @param index 顺序
     * @return 章节
     */
    public PicaPhoto getPhoto(int index) {
        List<PicaPhoto> ordered = new ArrayList<>(photos);
        ordered.sort(Comparator.comparingInt(PicaPhoto::getOrder));
        return ordered.get(index - 1);
    }

    /**
     * 是否单行本
     *
     * @return 是否单行本
     */
    public boolean isSingleAlbum() {
        return photos.size() == 1;
    }
}
