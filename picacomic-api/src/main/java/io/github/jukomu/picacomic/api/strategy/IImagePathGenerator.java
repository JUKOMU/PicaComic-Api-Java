package io.github.jukomu.picacomic.api.strategy;

import io.github.jukomu.picacomic.api.model.PicaImage;

import java.nio.file.Path;

/**
 * @author JUKOMU
 * @Description: 一个用于生成单张图片文件保存路径的策略接口
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/21
 */
@FunctionalInterface
public interface IImagePathGenerator {
    /**
     * 根据给定的图片信息，生成一个绝对路径
     *
     * @param image 图片实体
     * @return 资源应保存到的绝对路径
     */
    Path generatePath(PicaImage image);
}
