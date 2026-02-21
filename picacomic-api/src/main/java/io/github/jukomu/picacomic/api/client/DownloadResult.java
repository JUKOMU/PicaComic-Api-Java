package io.github.jukomu.picacomic.api.client;

import io.github.jukomu.picacomic.api.model.PicaImage;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @author JUKOMU
 * @Description: 封装批量下载操作的执行结果报告
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public final class DownloadResult {
    private final List<Path> successfulFiles;
    private final Map<PicaImage, Exception> failedTasks;

    public DownloadResult(List<Path> successfulFiles, Map<PicaImage, Exception> failedTasks) {
        this.successfulFiles = Collections.unmodifiableList(successfulFiles);
        this.failedTasks = Collections.unmodifiableMap(failedTasks);
    }

    /**
     * 获取所有成功下载并保存的文件路径列表
     */
    public List<Path> getSuccessfulFiles() {
        return successfulFiles;
    }

    /**
     * 获取所有失败的下载任务
     * Map 的 Key 是尝试下载的 PicaImage，Value 是导致失败的异常
     */
    public Map<PicaImage, Exception> getFailedTasks() {
        return failedTasks;
    }

    /**
     * 检查是否所有任务都已成功
     *
     * @return 如果没有失败的任务，则返回 true
     */
    public boolean isAllSuccess() {
        return failedTasks.isEmpty();
    }
}
