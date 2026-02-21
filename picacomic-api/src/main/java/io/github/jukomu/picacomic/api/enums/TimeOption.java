package io.github.jukomu.picacomic.api.enums;

/**
 * @author JUKOMU
 * @Description: 定义排行榜时间参数
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/22
 */
public enum TimeOption {
    /**
     * 24小时
     */
    DAY1("H24"),
    /**
     * 7天
     */
    DAY7("D7"),
    /**
     * 30天
     */
    DAY30("D30");

    private final String value;

    TimeOption(String value) {
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
