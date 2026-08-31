<p align="center">
  <a href="./README_en.md">English</a>
  <span>&nbsp;</span>
  <strong>中文</strong>
</p>

# Java API For PicaComic (哔咔漫画)

![Java](https://img.shields.io/badge/Java-17+-blue.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)
<!-- ![Maven Central](https://img.shields.io/maven-central/v/io.github.jukomu/picacomic-core) -->

**一个用于获取 PicaComic (哔咔漫画) 数据的 Java API 库**

---

## ⚠️ 项目状态：开发阶段 ⚠️

**请注意**: 本项目目前正处于积极的开发和测试阶段。API 可能会在未来的版本中发生不兼容的变更。

**Please note**: This project is currently in an active development and testing phase. The API may undergo incompatible changes in future versions.

---

## 功能概述

| 额外功能 | 实现情况 |
|:---------------------------|:---------------------------------|
| **API/image 网络边界** | ✅ 双 OkHttp client、固定 host 策略 |
| **图片大小与并发边界** | ✅ 单图最多 32 MiB、client 级 64 MiB budget |
| **原子图片落盘** | ✅ 同目录临时文件 + `ATOMIC_MOVE` |



### ✨ 已实现功能列表

#### 1. 核心数据获取层
获取并解析哔咔漫画 (PicaComic) 的基础实体信息。
- [x] **获取本子详情 (`getAlbum`)**：获取漫画的标题、作者、标签、简介等详细信息。
- [x] **获取章节详情 (`getPhoto`)**：获取指定章节下的所有图片列表。
- [x] **获取图片数据 (`fetchImageBytes`)**：根据图片信息直接拉取二进制数据。
- [x] **漫画搜索 (`search`)**：支持按关键字和指定条件（通过 `SearchQuery`）进行分页搜索。
- [x] **获取收藏夹 (`getFavorites`)**：获取当前登录用户收藏的漫画列表。
- [x] **获取分类数据 (`getCategories`)**：按分类获取漫画列表内容。
- [x] **获取排行榜 (`getLeaderboard`)**：支持根据不同时间范围（`TimeOption`）获取漫画排行榜。
- [x] **获取骑士榜 (`getKnightLeaderboard`)**：获取社区贡献排名（骑士榜）。
- [x] **获取随机本子 (`getRandomAlbums`)**：获取一组随机推荐的漫画。
- [x] **获取用户信息 (`getUserInfo`)**：获取当前登录账户的详细信息。

#### 2. 会话管理层
管理 PicaComic 客户端的身份认证与会话状态。
- [x] **用户登录 (`login`)**：支持使用用户名或邮箱与密码进行账号登录，完成 Token 凭证获取及维护。

#### 3. 智能下载操作层 (便利操作)
内置高度灵活且支持并发的下载工具。
- [x] **图片下载 (`downloadImage`)**：
  - 支持直接下载单张图片到默认路径。
  - 支持通过传入 URL 或图片实体进行精确下载。
  - 支持利用 `IImagePathGenerator` 策略接口自定义图片存储路径。
- [x] **章节下载 (`downloadPhoto`)**：
  - 支持并发下载整个章节内的所有图片。
  - 提供默认路径或自定义路径生成器 (`IPhotoPathGenerator`)。
  - 允许外部传入自定义的 `ExecutorService` 线程池以管理并发资源。
  - 返回统一的 `DownloadResult` 分析报告。
- [x] **全本下载 (`downloadAlbum`)**：
  - 支持全自动、高并发下载整本漫画的所有章节。
  - 提供默认路径或自定义路径生成器 (`IAlbumPathGenerator`)。
  - 允许外部传入自定义的 `ExecutorService` 线程池。
  - 返回统一的 `DownloadResult` 记录成功和失败的任务细节。

本项目采用模块化设计，将 **公共接口(API)** 与 **核心实现(Core)** 分离。

### `picacomic-api` 模块: 接口与数据模型

此模块定义了库的公共契约，不包含第三方网络库依赖，可独立集成。

* **领域模型**: 提供一套不可变数据对象，用于描述 `PicaAlbum` (本子), `PicaPhoto` (章节), `PicaImage` (图片), `PicaContentPage` (分页内容), `PicaUserInfo` (用户信息) 等核心实体。
* **客户端接口 (`IPicaClient`)**: 抽象并统一了所有业务操作，包括实体获取 (`getAlbum`, `getPhoto`)、列表查询 (`search`, `getCategories`, `getFavorites`)、排行榜查询 (`getLeaderboard`, `getKnightLeaderboard`)、用户会话 (`login`) 和下载 (`downloadAlbum`, `downloadPhoto` 等)。
* **策略接口**: 定义了如 `IAlbumPathGenerator`, `IPhotoPathGenerator` 等策略接口，允许调用者注入自定义逻辑来控制文件存储等外部交互行为。

### `picacomic-core` 模块: 核心实现

此模块包含了所有功能的具体实现逻辑，处理与 PicaComic 服务端的直接交互。

* **客户端实现**:
    * **API 请求**: 通过封装 OkHttp 调用 PicaComic 移动端 API 进行数据交互。
    * **会话管理**: 管理用户的登录状态、Token 凭证维护。
* **网络处理**:
    * **API/image 分离**: 每个 `IPicaClient` 独占 API 与图片两只 OkHttp client。图片 client 不带 Cookie、Token、签名或 API interceptor。
    * **安全重试**: 只对 API 的幂等 `GET`/`HEAD` 及 `502/503/504` 或 I/O 错误进行有限直连重试；POST 不自动重发，API 也不自动跟随 redirect。
    * **图片来源**: 图片只允许以下六个精确 HTTPS host，且路径必须位于 `/static/` 下：`img.picacomic.com`、`s2.picacomic.com`、`s3.picacomic.com`、`storage.picacomic.com`、`storage1.picacomic.com`、`storage-b.picacomic.com`。库不会从 API response、redirect 或运行时配置学习新 host。
* **并发下载**:
    * 提供了 `downloadAlbum` 和 `downloadPhoto` 等高级方法，叶子图片任务使用调用者提供或 client 自有的 `ExecutorService`。
    * 批量下载操作返回 `DownloadResult` 对象，其中包含了成功与失败任务的详细报告。
    * 图片单次请求通过 `PicaImageRequest.execute/cancel/close` 保持同步 API，同时允许页面级取消。单图上限为 32 MiB，每个 client 的在途 payload budget 固定为 64 MiB，默认图片并发为 2（可配置范围 1..4）。
    * 下载文件先完整写入同目录 `.part` 文件，再执行 `ATOMIC_MOVE`；不支持原子移动时失败，不降级为直接覆盖最终文件。

---

## 设计哲学

本项目的核心是一个**数据获取与管理工具**，而非一个功能固化的下载应用。其设计基于以下原则：

* **控制权移交**: 库本身不负责具体的线程调度和文件 I/O 的硬编码。调用者可以通过注入 `ExecutorService` 和自定义 `PathGenerator` 等策略接口，完全掌控并发行为和文件存储逻辑。
* **过程透明化**: API 调用链 (`Album` → `Photo` → `Image`) 中的每一个中间数据模型都是可访问的。这允许开发者在下载过程的任何阶段进行检查、过滤或自定义处理。
* **为集成而设计**: 库的目标是作为一个可靠的底层模块，被轻松地集成到其他大型应用中，如 Android App、桌面工具或后端服务。

---

## 安装 (Installation)

本项目尚未发布到 Maven 中央仓库。您可以通过以下方式在本地使用：

1. 克隆本仓库:
   ```bash
   git clone https://github.com/JUKOMU/PicaComic-Api-Java.git
   cd PicaComic-Api-Java
   ```

2. 在本地 Maven 仓库中安装:
   ```bash
   mvn clean install
   ```

3. 在您的 `pom.xml` 文件中添加依赖:
   ```xml
   <dependency>
       <groupId>io.github.jukomu</groupId>
       <artifactId>picacomic-core</artifactId>
       <version>0.0.1</version>
   </dependency>
   ```

---

## 快速上手 (Quick Start)

**注意：与部分漫画平台不同，获取 PicaComic 的大部分数据必须先进行用户登录。**

以下是一个基础下载器的完整示例：

```java
package io.github.jukomu.picacomic.sample;

import io.github.jukomu.picacomic.api.client.DownloadResult;
import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.model.PicaAlbum;
import io.github.jukomu.picacomic.core.PicaComic;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;

/**
 * @author JUKOMU
 * @Description: 下载器示例
 */
public class DownloaderSample {

    public static void main(String[] args) {
        // 1. 配置并构建 Client
        PicaConfiguration config = new PicaConfiguration.Builder().build();
        
        try (IPicaClient client = PicaComic.newApiClient(config)) {
            // 2. 登录账户 (必须操作)
            System.out.println("Logging in...");
            client.login("your_username_or_email", "your_password");
            
            // 3. 下载指定的本子
            downloadAlbumWithAllPhotos(client, "album_id_here");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void downloadAlbumWithAllPhotos(IPicaClient client, String albumId) {
        // 获取本子的信息
        PicaAlbum album = client.getAlbum(albumId);
        System.out.println("Downloading album: " + album.getTitle() + " ...");
        
        // 开始并发下载
        DownloadResult result = client.downloadAlbum(album);
        
        // 处理下载结果
        if (result.isAllSuccess()) {
            System.out.println("Download complete! All " + result.getSuccessfulFiles().size() + " images saved.");
        } else {
            System.out.println("Download partially complete.");
            System.out.println("Success: " + result.getSuccessfulFiles().size());
            System.out.println("Failed: " + result.getFailedTasks().size());
            result.getFailedTasks().forEach((image, error) ->
                    System.err.println("  - Failed to download " + image.getOriginalName() + ": " + error.getMessage())
            );
        }
    }
}
```

---

## 进阶用法 (Advanced Usage)

### 自定义网络配置

通过 `PicaConfiguration.Builder`，你可以精细化地控制网络请求、并发模型及图片质量等参数。

```java
import java.time.Duration;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
// import io.github.jukomu.picacomic.api.enums.ImageQuality;

PicaConfiguration config = new PicaConfiguration.Builder()
        .proxy("127.0.0.1", 7890) // 可选：调用者显式提供的 HTTP 代理
        .timeout(Duration.ofSeconds(60)) // 设置网络超时为60秒
        .retryTimes(5) // 设置最大重试次数
        .downloadThreadPoolSize(12) // 设置内部下载线程池大小
        .concurrentPhotoDownloads(3) // 设置同时下载的章节数
        .concurrentImageDownloads(2) // 设置同时读取图片的数量（范围 1..4）
        .maxImageBytes(32L * 1024 * 1024) // 单图上限；client 级 64 MiB budget 由库固定管理
        // .imageQuality(ImageQuality.ORIGINAL) // 设置下载的图片质量
        .build();
```

代理只作用于该配置创建的 client，可能观察网络元数据；库不会因失败自动启用或切换代理。图片请求始终创建空白 HTTPS `GET`，手动校验最多三跳 redirect，并且不会继承 API 的 Cookie、Token、签名或请求体。

`fetchImageBytes` 是阻塞便利方法；需要取消时使用 `PicaImageRequest request = client.newImageRequest(image)`，在自己的 executor 中调用 `request.execute()`，并在页面销毁时调用 `request.cancel()`/`request.close()`。返回的 `byte[]` 在便利方法返回后归调用者所有，库不限制调用者长期保留它。

图片失败统一抛出 `ImageFetchException`，可通过 `getReason()` 判断 `INVALID_SOURCE`、`DISALLOWED_HOST`、`REDIRECT_REJECTED`、`TOO_LARGE`、`UNSUPPORTED_MEDIA_TYPE`、`TIMEOUT`、`CANCELLED` 或 `CLIENT_CLOSED` 等稳定原因。README 中的示例凭据仅为占位文本；本项目的测试只访问本地 TLS fixture，不访问真实服务。

### 自定义文件存储路径

如果不希望使用默认的文件保存路径，您可以自定义实现路径生成策略：

```java
import io.github.jukomu.picacomic.api.strategy.IAlbumPathGenerator;
import java.nio.file.Path;

IAlbumPathGenerator generator = new IAlbumPathGenerator() {
    @Override
    public Path generatePath(PicaAlbum album) {
        // 示例：将本子存储到 "作者\标题_ID" 的特定目录
        return Path.of("MyDownloads",
                cleanFileName(album.getAuthor()),
                cleanFileName(album.getTitle()) + "_" + album.getId());
    }
    
    private String cleanFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
};

// 传入策略进行下载
client.downloadAlbum(album, generator);
```

### 使用外部线程池

如果您在更复杂的应用中（例如 Spring Boot 或 Android）已经拥有全局的线程池，可以直接注入以复用资源：

```java
// 创建并管理你自己的线程池
ExecutorService myExecutor = Executors.newFixedThreadPool(16);

try {
    // 将线程池注入到下载方法中
    DownloadResult result = client.downloadAlbum(album, pathGenerator, myExecutor);
} finally {
    // 在应用退出时，由您自己负责关闭线程池
    myExecutor.shutdown();
}
```

---

## 如何贡献 (Contributing)

欢迎任何形式的贡献！如果您发现了 BUG 或有新的功能建议，请随时提交 [Issues](https://github.com/JUKOMU/PicaComic-Api-Java/issues)。

如果您想贡献代码，请先 Fork 本项目，在您的分支上进行修改，然后提交 Pull Request。

---

## 许可证 (License)

本项目基于 [MIT License](LICENSE) 开源。
