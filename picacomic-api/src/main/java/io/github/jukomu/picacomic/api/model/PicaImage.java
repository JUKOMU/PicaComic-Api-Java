package io.github.jukomu.picacomic.api.model;

/**
 * @author JUKOMU
 * @Description: 代表图片资源对象（封面、头像等）
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/20
 */
public record PicaImage(
        String originalName,
        String path,
        String fileServer,
        String imageUrl
) {

    public String getOriginalName() {
        return originalName;
    }

    public String getPath() {
        return path;
    }

    public String getFileServer() {
        return fileServer;
    }

    /**
     * 构建完整的图片 URL
     */
    public String getImageUrl() {
        if (imageUrl != null && !imageUrl.isBlank()) {
            return imageUrl;
        }
        if (fileServer == null || fileServer.isBlank() || path == null || path.isBlank()) {
            throw new IllegalStateException("Image locator is incomplete");
        }
        String fs = fileServer.endsWith("/") ? fileServer.substring(0, fileServer.length() - 1) : fileServer;
        String p = path.startsWith("/") ? path.substring(1) : path;
        return fs + "/static/" + p;
    }
}
