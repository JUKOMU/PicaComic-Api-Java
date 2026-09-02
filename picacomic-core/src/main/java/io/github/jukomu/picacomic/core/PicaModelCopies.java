package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.model.PicaAlbum;
import io.github.jukomu.picacomic.api.model.PicaContentPage;
import io.github.jukomu.picacomic.api.model.PicaImage;
import io.github.jukomu.picacomic.api.model.PicaPhoto;
import io.github.jukomu.picacomic.api.model.PicaUserInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Explicit recursive copies for the public model graph.
 *
 * <p>Core state stores one private graph and every public boundary receives a
 * separate mutable graph. Keeping the fields explicit makes ownership changes
 * auditable when a model gains another nested collection.</p>
 */
final class PicaModelCopies {

    private PicaModelCopies() {
    }

    static PicaImage image(PicaImage source) {
        if (source == null) {
            return null;
        }
        return new PicaImage(source.originalName(), source.path(), source.fileServer(), source.imageUrl());
    }

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
