<p align="center">
  <a href="./README.md">中文</a>
  <span>&nbsp;</span>
  <strong>English</strong>
</p>

# Java API For PicaComic

![Java](https://img.shields.io/badge/Java-17+-blue.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)
<!-- ![Maven Central](https://img.shields.io/maven-central/v/io.github.jukomu/picacomic-core) -->

**A Java API library for fetching PicaComic data**

---

## ⚠️ Project Status: In Development ⚠️

**Please note**: This project is currently in an active development and testing phase. The API may undergo incompatible changes in future versions.

---

## Features Overview

| Additional Features | Implementation Status |
|:---------------------------|:---------------------------------|
| **Built-in Image Proxy (using wsrv.nl)** | ✅ |

Below is a list of implemented features based on the current `PicaComic-Api-Java` repository:

### ✨ Implemented Features

#### 1. Core Data Fetching Layer
Fetches and parses basic entity information from PicaComic.
- [x] **Get Album Details (`getAlbum`)**: Retrieve detailed information such as the comic's title, author, tags, and description.
- [x] **Get Photo Details (`getPhoto`)**: Retrieve the list of all images under a specific chapter (photo).
- [x] **Fetch Image Data (`fetchImageBytes`)**: Directly pull binary data based on image information.
- [x] **Comic Search (`search`)**: Supports paginated search by keywords and specified conditions (via `SearchQuery`).
- [x] **Get Favorites (`getFavorites`)**: Retrieve the list of comics bookmarked by the currently logged-in user.
- [x] **Get Categories (`getCategories`)**: Retrieve comic lists by category.
- [x] **Get Leaderboard (`getLeaderboard`)**: Supports fetching comic leaderboards based on different time ranges (`TimeOption`).
- [x] **Get Knight Leaderboard (`getKnightLeaderboard`)**: Retrieve the community contribution ranking (Knight Leaderboard).
- [x] **Get Random Albums (`getRandomAlbums`)**: Retrieve a set of randomly recommended comics.
- [x] **Get User Info (`getUserInfo`)**: Retrieve detailed information of the currently logged-in account.

#### 2. Session Management Layer
Manages authentication and session state for the PicaComic client.
- [x] **User Login (`login`)**: Supports logging in with a username or email and password, completing Token credential acquisition and maintenance.

#### 3. Smart Download Operations Layer (Convenience Operations)
Built-in, highly flexible, and concurrent-supported downloading tools.
- [x] **Download Image (`downloadImage`)**:
  - Supports downloading a single image directly to the default path.
  - Supports precise downloading by passing a URL or image entity.
  - Supports customizing the image storage path using the `IImagePathGenerator` strategy interface.
- [x] **Download Photo/Chapter (`downloadPhoto`)**:
  - Supports concurrent downloading of all images within an entire chapter.
  - Provides a default path or a custom path generator (`IPhotoPathGenerator`).
  - Allows passing an external custom `ExecutorService` thread pool to manage concurrent resources.
  - Returns a unified `DownloadResult` analysis report.
- [x] **Download Album (`downloadAlbum`)**:
  - Supports fully automatic, highly concurrent downloading of all chapters in an entire comic.
  - Provides a default path or a custom path generator (`IAlbumPathGenerator`).
  - Allows passing an external custom `ExecutorService` thread pool.
  - Returns a unified `DownloadResult` recording the details of successful and failed tasks.

This project adopts a modular design, separating the **Public Interfaces (API)** from the **Core Implementation (Core)**.

### `picacomic-api` Module: Interfaces and Data Models

This module defines the public contract of the library. It contains no third-party network library dependencies and can be integrated independently.

* **Domain Models**: Provides a set of immutable data objects to describe core entities like `PicaAlbum` (Album), `PicaPhoto` (Chapter), `PicaImage` (Image), `PicaContentPage` (Paginated Content), and `PicaUserInfo` (User Info).
* **Client Interface (`IPicaClient`)**: Abstracts and unifies all business operations, including entity fetching (`getAlbum`, `getPhoto`), list querying (`search`, `getCategories`, `getFavorites`), leaderboard querying (`getLeaderboard`, `getKnightLeaderboard`), user sessions (`login`), and downloading (`downloadAlbum`, `downloadPhoto`, etc.).
* **Strategy Interfaces**: Defines strategy interfaces like `IAlbumPathGenerator`, `IPhotoPathGenerator`, etc., allowing callers to inject custom logic to control external interactions such as file storage.

### `picacomic-core` Module: Core Implementation

This module contains the specific implementation logic for all features, handling direct interactions with the PicaComic server.

* **Client Implementation (`PicaClient`)**:
    * **API Requests**: Encapsulates OkHttp to call the PicaComic mobile API for data interaction.
    * **Session Management**: Manages user login states and Token credential maintenance.
* **Network Processing**:
    * **Distribution and Domains (`PicaDomainManager`)**: Contains the mechanism for dynamically fetching and managing API domains.
    * **Request Retry and Proxy Fallback:**
      * Implements a reliable retry logic and provides a **built-in public proxy fallback mechanism** when requests fail multiple times.
      * For blocked image domains (e.g., those restricted by the Great Firewall in certain regions), when the number of retries reaches a specified threshold (default `proxyFallbackThreshold` is 2), the system will automatically redirect subsequent requests to a public image proxy service (currently using `wsrv.nl`).
      * Users can also override the built-in mechanism through configuration options to use their own proxy service.
* **Concurrent Downloading**:
    * Provides advanced methods like `downloadAlbum` and `downloadPhoto` with built-in concurrent download scheduling capabilities based on `ExecutorService`.
    * Batch download operations return a `DownloadResult` object, which contains detailed reports of successful and failed tasks.

---

## Design Philosophy

The core of this project is a **data fetching and management tool**, rather than a rigidly functioned download application. Its design is based on the following principles:

* **Inversion of Control**: The library itself does not hardcode specific thread scheduling or file I/O. Callers can completely control concurrent behavior and file storage logic by injecting an `ExecutorService` and custom `PathGenerator` strategy interfaces.
* **Process Transparency**: Every intermediate data model in the API call chain (`Album` → `Photo` → `Image`) is accessible. This allows developers to inspect, filter, or customize processing at any stage of the download process.
* **Designed for Integration**: The goal of the library is to serve as a reliable foundational module that can be easily integrated into other large-scale applications, such as Android apps, desktop tools, or backend services.

---

## Installation

This project has not yet been published to the Maven Central Repository. You can use it locally by following these steps:

1. Clone this repository:
   ```bash
   git clone https://github.com/JUKOMU/PicaComic-Api-Java.git
   cd PicaComic-Api-Java
   ```

2. Install it in your local Maven repository:
   ```bash
   mvn clean install
   ```

3. Add the dependency to your `pom.xml` file:
   ```xml
   <dependency>
       <groupId>io.github.jukomu</groupId>
       <artifactId>picacomic-core</artifactId>
       <version>0.0.1</version>
   </dependency>
   ```

---

## Quick Start

**Note: Unlike some comic platforms, fetching most data from PicaComic requires user login first.**

Below is a complete example of a basic downloader:

```java
package io.github.jukomu.picacomic.sample;

import io.github.jukomu.picacomic.api.client.DownloadResult;
import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.model.PicaAlbum;
import io.github.jukomu.picacomic.core.PicaComic;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;

/**
 * @author JUKOMU
 * @Description: Downloader Sample
 */
public class DownloaderSample {

    public static void main(String[] args) {
        // 1. Configure and build the Client
        PicaConfiguration config = new PicaConfiguration.Builder().build();
        
        try (IPicaClient client = PicaComic.newApiClient(config)) {
            // 2. Log in to the account (Required)
            System.out.println("Logging in...");
            client.login("your_username_or_email", "your_password");
            
            // 3. Download the specified album
            downloadAlbumWithAllPhotos(client, "album_id_here");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void downloadAlbumWithAllPhotos(IPicaClient client, String albumId) {
        // Get album information
        PicaAlbum album = client.getAlbum(albumId);
        System.out.println("Downloading album: " + album.getTitle() + " ...");
        
        // Start concurrent download
        DownloadResult result = client.downloadAlbum(album);
        
        // Handle download results
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

## Advanced Usage

### Custom Network Configuration

Through `PicaConfiguration.Builder`, you can finely control parameters such as network requests, the concurrency model, and image quality.

```java
import java.time.Duration;
import io.github.jukomu.picacomic.core.config.PicaConfiguration;
// import io.github.jukomu.picacomic.api.enums.ImageQuality;

PicaConfiguration config = new PicaConfiguration.Builder()
        .proxy("127.0.0.1", 7890) // Set HTTP/SOCKS proxy (Recommended for users in mainland China)
        .timeout(Duration.ofSeconds(60)) // Set network timeout to 60 seconds
        .retryTimes(5) // Set maximum retry times
        .proxyFallbackThreshold(2) // Proxy fallback threshold
        .downloadThreadPoolSize(12) // Set internal download thread pool size
        .concurrentPhotoDownloads(3) // Set the number of concurrent chapter downloads
        .concurrentImageDownloads(20) // Set the number of concurrent image downloads
        // .imageQuality(ImageQuality.ORIGINAL) // Set downloaded image quality
        .build();
```

### Custom File Storage Path

If you do not wish to use the default file save path, you can implement a custom path generation strategy:

```java
import io.github.jukomu.picacomic.api.strategy.IAlbumPathGenerator;
import java.nio.file.Path;

IAlbumPathGenerator generator = new IAlbumPathGenerator() {
    @Override
    public Path generatePath(PicaAlbum album) {
        // Example: Store the album in a specific directory "Author\Title_ID"
        return Path.of("MyDownloads",
                cleanFileName(album.getAuthor()),
                cleanFileName(album.getTitle()) + "_" + album.getId());
    }
    
    private String cleanFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
};

// Pass the strategy to perform the download
client.downloadAlbum(album, generator);
```

### Using an External Thread Pool

If you already have a global thread pool in a more complex application (such as Spring Boot or Android), you can inject it directly to reuse resources:

```java
// Create and manage your own thread pool
ExecutorService myExecutor = Executors.newFixedThreadPool(16);

try {
    // Inject the thread pool into the download method
    DownloadResult result = client.downloadAlbum(album, pathGenerator, myExecutor);
} finally {
    // You are responsible for shutting down the thread pool when the application exits
    myExecutor.shutdown();
}
```

---

## Contributing

Any form of contribution is welcome! If you find a bug or have new feature suggestions, please feel free to submit an [Issue](https://github.com/JUKOMU/PicaComic-Api-Java/issues).

If you want to contribute code, please Fork this project first, make your changes on your branch, and then submit a Pull Request.

---

## License

This project is open-sourced under the [MIT License](LICENSE).
