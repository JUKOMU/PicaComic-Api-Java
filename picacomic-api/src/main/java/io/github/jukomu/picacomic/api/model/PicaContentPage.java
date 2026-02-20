package io.github.jukomu.picacomic.api.model;

import java.util.List;

/**
 * @author JUKOMU
 * @Description: 代表包含本子的页面
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/20
 */
public record PicaContentPage(
        int page,
        int pages,
        int total,
        int limit,
        List<PicaAlbum> albums
) {
    /**
     * 获取当前页码
     *
     * @return 当前页数 (从1开始)
     */
    public int getPage() {
        return page;
    }

    /**
     * 获取总页数
     *
     * @return 总页数
     */
    public int getPages() {
        return pages;
    }

    /**
     * 获取数据总条数
     *
     * @return 漫画总数量
     */
    public int getTotal() {
        return total;
    }

    /**
     * 获取每页限制条数
     *
     * @return 每页数量 (通常为20或40)
     */
    public int getLimit() {
        return limit;
    }

    /**
     * 获取本页的漫画列表
     *
     * @return 漫画简略信息列表
     */
    public List<PicaAlbum> getDocs() {
        return albums;
    }
}
