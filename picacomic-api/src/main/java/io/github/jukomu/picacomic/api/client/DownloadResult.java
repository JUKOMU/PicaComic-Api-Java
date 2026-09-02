package io.github.jukomu.picacomic.api.client;

import io.github.jukomu.picacomic.api.model.PicaImage;

import java.nio.file.Path;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author JUKOMU
 * @Description: 封装批量下载操作的执行结果报告
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 *
 * <p>结果中的集合是创建时的只读快照；失败映射的 key 是尝试下载的图片，value
 * 是对应的失败异常。</p>
 */
public final class DownloadResult {
    private final List<Path> successfulFiles;
    private final Map<PicaImage, Exception> failedTasks;

    /**
     * 创建一个批量下载结果快照。
     *
     * @param successfulFiles 已成功保存的文件路径
     * @param failedTasks 未成功保存的图片及其异常
     */
    public DownloadResult(List<Path> successfulFiles, Map<PicaImage, Exception> failedTasks) {
        this.successfulFiles = Collections.unmodifiableList(new ArrayList<>(
                successfulFiles == null ? List.of() : successfulFiles));
        this.failedTasks = Collections.unmodifiableMap(new LinkedHashMap<>(
                failedTasks == null ? Map.of() : failedTasks));
    }

    /**
     * 获取所有成功下载并保存的文件路径列表。
     *
     * @return 成功文件路径的只读列表
     */
    public List<Path> getSuccessfulFiles() {
        return successfulFiles;
    }

    /**
     * 获取所有失败的下载任务。
     *
     * <p>Map 的 key 是尝试下载的 {@link PicaImage}，value 是导致失败的异常。</p>
     *
     * @return 失败任务的只读映射
     */
    public Map<PicaImage, Exception> getFailedTasks() {
        return failedTasks;
    }

    /**
     * 检查是否所有任务都已成功。
     *
     * @return 如果没有失败的任务，则返回 true
     */
    public boolean isAllSuccess() {
        return failedTasks.isEmpty();
    }
}
