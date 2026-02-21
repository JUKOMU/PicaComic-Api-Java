package io.github.jukomu.picacomic.core.strategy.impl;

import io.github.jukomu.picacomic.api.model.PicaImage;
import io.github.jukomu.picacomic.api.strategy.IImagePathGenerator;
import io.github.jukomu.picacomic.core.util.FileUtils;

import java.nio.file.Path;

/**
 * @author JUKOMU
 * @Description: 图片下载路径生成器的默认实现
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/21
 */
public class DefaultImagePathGenerator implements IImagePathGenerator {
    @Override
    public Path generatePath(PicaImage image) {
        return Path.of(FileUtils.sanitizeFilename(image.getOriginalName()));
    }
}
