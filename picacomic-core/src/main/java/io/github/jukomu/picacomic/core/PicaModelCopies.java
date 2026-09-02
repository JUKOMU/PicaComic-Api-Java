package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.model.PicaAlbum;
import io.github.jukomu.picacomic.api.model.PicaContentPage;
import io.github.jukomu.picacomic.api.model.PicaImage;
import io.github.jukomu.picacomic.api.model.PicaPhoto;
import io.github.jukomu.picacomic.api.model.PicaUserInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 负责复制公开 model 图的递归快照工具。
 *
 * <p>core 状态只保存一份内部图；每个公开边界都获得独立且可修改的图。显式列出
 * 每个字段可以在 model 增加嵌套集合时清楚地检查所有权边界。</p>
 */
final class PicaModelCopies {

    private PicaModelCopies() {
    }

    /**
     * 复制图片模型。
     *
     * @param source 原图片模型
     * @return 独立图片副本；输入为 {@code null} 时返回 {@code null}
     */
    static PicaImage image(PicaImage source) {
        if (source == null) {
            return null;
        }
        return new PicaImage(source.originalName(), source.path(), source.fileServer(), source.imageUrl());
    }

    /**
     * 复制用户模型及其嵌套集合和头像。
     *
     * @param source 原用户模型
     * @return 独立用户副本；输入为 {@code null} 时返回 {@code null}
     */
    static PicaUserInfo user(PicaUserInfo source) {
        if (source == null) {
            return null;
        }
        return new PicaUserInfo(
                source.id(),
                source.name(),
                source.email(),
                source.slogan(),
                source.birthday(),
                source.gender(),
                source.title(),
                source.verified(),
                source.exp(),
                source.level(),
                copyStrings(source.characters()),
                source.createdAt(),
                image(source.avatar()),
                source.isPunched(),
                source.comicsUploaded());
    }

    /**
     * 复制章节模型及其图片列表。
     *
     * @param source 原章节模型
     * @return 独立章节副本；输入为 {@code null} 时返回 {@code null}
     */
    static PicaPhoto photo(PicaPhoto source) {
        if (source == null) {
            return null;
        }
        return new PicaPhoto(
                source.albumId(),
                source.id(),
                source.title(),
                source.updatedAt(),
                source.order(),
                copyImages(source.images()),
                source.isSingleAlbum());
    }

    /**
     * 复制本子模型及其递归嵌套对象。
     *
     * @param source 原本子模型
     * @return 独立本子副本；输入为 {@code null} 时返回 {@code null}
     */
    static PicaAlbum album(PicaAlbum source) {
        if (source == null) {
            return null;
        }
        return new PicaAlbum(
                source.id(),
                user(source.creator()),
                source.title(),
                source.description(),
                image(source.thumb()),
                source.author(),
                source.chineseTeam(),
                copyStrings(source.categories()),
                copyStrings(source.tags()),
                source.pagesCount(),
                source.epsCount(),
                source.finished(),
                source.updatedAt(),
                source.createdAt(),
                source.allowDownload(),
                source.allowComment(),
                source.totalLikes(),
                source.totalViews(),
                source.totalComments(),
                source.viewsCount(),
                source.likesCount(),
                source.commentsCount(),
                source.isFavourite(),
                source.isLiked(),
                copyPhotos(source.photos()));
    }

    /**
     * 复制分页模型及其中的本子列表。
     *
     * @param source 原分页模型
     * @return 独立分页副本；输入为 {@code null} 时返回 {@code null}
     */
    static PicaContentPage contentPage(PicaContentPage source) {
        if (source == null) {
            return null;
        }
        return new PicaContentPage(
                source.page(),
                source.pages(),
                source.total(),
                source.limit(),
                copyAlbums(source.albums()));
    }

    /**
     * 递归复制用户列表。
     *
     * @param source 原用户列表
     * @return 独立用户列表；输入为 {@code null} 时返回 {@code null}
     */
    static List<PicaUserInfo> users(List<PicaUserInfo> source) {
        if (source == null) {
            return null;
        }
        List<PicaUserInfo> copy = new ArrayList<>(source.size());
        for (PicaUserInfo user : source) {
            copy.add(user(user));
        }
        return copy;
    }

    private static List<String> copyStrings(List<String> source) {
        return source == null ? null : new ArrayList<>(source);
    }

    private static List<PicaImage> copyImages(List<PicaImage> source) {
        if (source == null) {
            return null;
        }
        List<PicaImage> copy = new ArrayList<>(source.size());
        for (PicaImage image : source) {
            copy.add(image(image));
        }
        return copy;
    }

    private static List<PicaPhoto> copyPhotos(List<PicaPhoto> source) {
        if (source == null) {
            return null;
        }
        List<PicaPhoto> copy = new ArrayList<>(source.size());
        for (PicaPhoto photo : source) {
            copy.add(photo(photo));
        }
        return copy;
    }

    private static List<PicaAlbum> copyAlbums(List<PicaAlbum> source) {
        if (source == null) {
            return null;
        }
        List<PicaAlbum> copy = new ArrayList<>(source.size());
        for (PicaAlbum album : source) {
            copy.add(album(album));
        }
        return copy;
    }
}
