package io.github.jukomu.picacomic.api.enums;

/**
 * @author JUKOMU
 * @Description: 定义搜索和分类时的排序选项
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/20
 * dd (新到旧), da (旧到新), ld (最多爱心), vd (最多指名)。
 */
public enum OrderBy {
    /**
     * 新到旧
     */
    LATEST("dd"),
    /**
     * 新到旧
     */
    EARLIEST("da"),
    /**
     * 最多爱心
     */
    MOST_LIKED("ld"),
    /**
     * 最多绅士指名次数
     */
    MOST_NOTED("vd");


    private final String value;

    OrderBy(String value) {
        this.value = value;
    }

    /**
     * 获取在API请求中使用的实际字符串值
     *
     * @return API参数值
     */
    public String getValue() {
        return value;
    }
}
