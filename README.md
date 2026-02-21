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

本项目采用模块化设计，将 **公共接口(API)** 与 **核心实现(Core)** 分离。

### `picacomic-api` 模块: 接口与数据模型

此模块定义了库的公共契约，不包含第三方网络库依赖，可独立集成。

* **领域模型**: 提供一套不可变数据对象，用于描述 `PicaAlbum` (本子), `PicaPhoto` (章节), `PicaImage` (图片), `PicaContentPage` (分页内容), `PicaUserInfo` (用户信息) 等核心实体。
* **客户端接口 (`IPicaClient`)**: 抽象并统一了所有业务操作，包括实体获取 (`getAlbum`, `getPhoto`)、列表查询 (`search`, `getCategories`, `getFavorites`)、排行榜查询 (`getLeaderboard`, `getKnightLeaderboard`)、用户会话 (`login`) 和下载 (`downloadAlbum`, `downloadPhoto` 等)。
* **策略接口**: 定义了如 `IAlbumPathGenerator`, `IPhotoPathGenerator` 等策略接口，允许调用者注入自定义逻辑来控制文件存储等外部交互行为。

### `picacomic-core` 模块: 核心实现

此模块包含了所有功能的具体实现逻辑，处理与 PicaComic 服务端的直接交互。

* **客户端实现 (`PicaClient`)**:
    * **API 请求**: 通过封装 OkHttp 调用 PicaComic 移动端 API 进行数据交互。
    * **会话管理**: 管理用户的登录状态、Token 凭证维护。
* **网络处理**:
    * **分发与域名 (`PicaDomainManager`)**: 包含动态拉取和管理 API 域名的机制。
    * **请求重试**: 实现了一套可靠的重试逻辑与代理回退机制。
* **并发下载**:
    * 提供了 `downloadAlbum` 和 `downloadPhoto` 等高级方法，内置了基于 `ExecutorService` 的并发下载调度能力。
    * 批量下载操作返回 `DownloadResult` 对象，其中包含了成功与失败任务的详细报告。

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
                    System.err.println("  - Failed to download " + image.getImageId() + ": " + error.getMessage())
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
        .proxy("127.0.0.1", 7890) // 设置HTTP/SOCKS代理 (推荐中国大陆用户配置)
        .timeout(Duration.ofSeconds(60)) // 设置网络超时为60秒
        .retryTimes(5) // 设置最大重试次数
        .proxyFallbackThreshold(2) // 代理回退阈值
        .downloadThreadPoolSize(12) // 设置内部下载线程池大小
        .concurrentPhotoDownloads(3) // 设置同时下载的章节数
        .concurrentImageDownloads(20) // 设置同时下载的图片数
        // .imageQuality(ImageQuality.ORIGINAL) // 设置下载的图片质量
        .build();
```

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
