package io.github.jukomu.picacomic.api.strategy;

import io.github.jukomu.picacomic.api.model.PicaPhoto;

import java.nio.file.Path;

/**
 * @author JUKOMU
 * @Description: 一个用于生成章节（Photo）级别文件或目录路径的策略接口
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/21
 */
@FunctionalInterface
public interface IPhotoPathGenerator {
    /**
     * 根据给定的本子信息，生成一个绝对路径
     *
     * @param photo 章节实体
     * @return 资源应保存到的绝对路径
     */
    Path generatePath(PicaPhoto photo);
}
