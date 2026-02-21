package io.github.jukomu.picacomic.core.strategy.impl;

import io.github.jukomu.picacomic.api.model.PicaPhoto;
import io.github.jukomu.picacomic.api.strategy.IPhotoPathGenerator;
import io.github.jukomu.picacomic.core.util.FileUtils;

import java.nio.file.Path;

/**
 * @author JUKOMU
 * @Description: 章节下载路径生成器的默认实现
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/21
 */
public class DefaultPhotoPathGenerator implements IPhotoPathGenerator {
    @Override
    public Path generatePath(PicaPhoto photo) {
        if (photo.isSingleAlbum()) {
            return Path.of("");
        }
        return Path.of(FileUtils.sanitizeFilename(String.valueOf(photo.getOrder())),
                photo.getTitle());
    }
}
