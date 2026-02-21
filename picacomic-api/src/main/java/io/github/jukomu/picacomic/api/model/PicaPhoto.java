package io.github.jukomu.picacomic.api.model;

import java.util.List;

/**
 * @author JUKOMU
 * @Description: 章节详情详细信息
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/20
 */
public record PicaPhoto(
        String albumId,
        String id,
        String title,
        String updatedAt,
        int order,
        List<PicaImage> images,
        boolean isSingleAlbum
) {
    /**
     * 获取所属本子(Album)的ID
     *
     * @return 本子ID
     */
    public String getAlbumId() {
        return albumId;
    }

    /**
     * 获取章节id
     *
     * @return 章节id
     */
    public String getId() {
        return id;
    }

    /**
     * 获取章节标题
     *
     * @return 章节标题
     */
    public String getTitle() {
        return title;
    }

    /**
     * 获取章节更新时间
     *
     * @return 章节更新时间
     */
    public String getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 获取章节在本子中的顺序
     *
     * @return 顺序
     */
    public int getOrder() {
        return order;
    }

    /**
     * 获取此章节包含的所有图片的列表
     *
     * @return 图片的列表
     */
    public List<PicaImage> getImages() {
        return images;
    }

    /**
     * 是否单行本
     *
     * @return 是否单行本
     */
    public boolean isSingleAlbum() {
        return isSingleAlbum;
    }
}
