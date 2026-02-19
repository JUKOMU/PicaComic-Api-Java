package io.github.jukomu.picacomic.api.model;

/**
 * @author JUKOMU
 * @Description: 代表用户头像信息
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/20
 */
public record PicaAvatar(
        String originalName,
        String path,
        String fileServer
) {
    /**
     * 构建完整的图片 URL
     */
    public String getImageUrl() {
        if (fileServer == null || path == null) {
            return "https://manhuabika.com/assets/placeholder_avatar_2-BAyIUBTE.png";
        }
        String fs = fileServer.endsWith("/") ? fileServer.substring(0, fileServer.length() - 1) : fileServer;
        String p = path.startsWith("/") ? path.substring(1) : path;
        return fs + "/static/" + p;
    }
}
