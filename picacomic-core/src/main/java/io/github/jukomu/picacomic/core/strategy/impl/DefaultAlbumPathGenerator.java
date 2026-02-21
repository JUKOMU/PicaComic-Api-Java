package io.github.jukomu.picacomic.core.strategy.impl;

import io.github.jukomu.picacomic.api.model.PicaAlbum;
import io.github.jukomu.picacomic.api.strategy.IAlbumPathGenerator;
import io.github.jukomu.picacomic.core.util.FileUtils;

import java.nio.file.Path;

/**
 * @author JUKOMU
 * @Description: 本子下载路径生成器的默认实现
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/21
 */
public class DefaultAlbumPathGenerator implements IAlbumPathGenerator {
    @Override
    public Path generatePath(PicaAlbum album) {
        return Path.of(FileUtils.sanitizeFilename(album.getAuthor()),
                FileUtils.sanitizeFilename(album.getTitle()),
                album.getId());
    }
}
