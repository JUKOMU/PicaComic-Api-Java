package io.github.jukomu.picacomic.api.enums;

/**
 * @author JUKOMU
 * @Description: 定义客户端图片质量
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/21
 */
public enum ImageQuality {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    ORIGINAL("original");


    private final String value;

    ImageQuality(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
