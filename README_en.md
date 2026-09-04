<p align="center">
  <a href="./README.md">中文</a>
  <span>&nbsp;</span>
  <strong>English</strong>
</p>

# Java API For PicaComic

![Java](https://img.shields.io/badge/Java-17+-blue.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)
<!-- Enable the Maven Central badge after the first immutable release. -->

**A Java API library for fetching PicaComic data**

---

## ⚠️ Project Status: In Development ⚠️

**Please note**: This project is currently in an active development and testing phase. The API may undergo incompatible changes in future versions.

---

## Features Overview

| Additional Features | Implementation Status |
|:---------------------------|:---------------------------------|
| **API/image network boundary** | ✅ Separate OkHttp clients with API host probing and retries |
| **Image concurrency boundary** | ✅ Per-client fair semaphore driven by configuration |
| **Atomic image landing** | ✅ Same-directory temporary file + `ATOMIC_MOVE` |



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
- [x] **User Login (`login`)**: Completes sign-in and profile atomically in one logical request; the client becomes signed in only after receiving a complete profile with a stable user ID.

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

* **Domain Models**: Provides record data objects for core entities like `PicaAlbum` (Album), `PicaPhoto` (Chapter), `PicaImage` (Image), `PicaContentPage` (Paginated Content), and `PicaUserInfo` (User Info). Public collections remain mutable; the client recursively copies them at cache and session boundaries.
* **Client Interface (`IPicaClient`)**: Abstracts and unifies all business operations, including entity fetching (`getAlbum`, `getPhoto`), list querying (`search`, `getCategories`, `getFavorites`), leaderboard querying (`getLeaderboard`, `getKnightLeaderboard`), user sessions (`login`), and downloading (`downloadAlbum`, `downloadPhoto`, etc.).
* **Strategy Interfaces**: Defines strategy interfaces like `IAlbumPathGenerator`, `IPhotoPathGenerator`, etc., allowing callers to inject custom logic to control external interactions such as file storage.

### `picacomic-core` Module: Core Implementation

This module contains the specific implementation logic for all features, handling direct interactions with the PicaComic server.

* **Client implementation**:
    * **API Requests**: Encapsulates OkHttp to call the PicaComic mobile API for data interaction.
    * **Session Management**: Manages `SIGNED_OUT`, `AUTHENTICATING`, `SIGNED_IN`, and `EXPIRED` states. Credentials exist only in the current client process; passwords are not retained and sessions are never restored automatically.
    * **Structured Errors**: API failures expose `PicaApiException.Reason`, the final HTTP status, provider code, and `Retry-After`; exception messages and causes never include response bodies, URLs, headers, or credentials.
* **Network Processing**:
    * **API/image separation**: Each `IPicaClient` owns two OkHttp clients. The image client has no Cookie, token, signature, or API interceptor.
    * **Safe retries**: API requests select the best host from the configured pool and retry, within `retryTimes`, on I/O, `403`, or any `5xx` response. GET and POST use the same rules. API requests use OkHttp's default redirect behavior.
    * **Image sources**: Image URLs come from the model or caller and are parsed by OkHttp. The image client carries no API credentials, follows default redirects, and accepts successful `2xx` bodies and standard gzip.
* **Concurrent Downloading**:
    * Provides advanced methods like `downloadAlbum` and `downloadPhoto`; only leaf image work is submitted to the caller-provided or client-owned `ExecutorService`.
    * Batch download operations return a `DownloadResult` object, which contains detailed reports of successful and failed tasks.
    * `PicaImageRequest.execute/cancel/close` keeps single-image access blocking while allowing page-level cancellation. Image concurrency defaults to 20 and accepts positive configuration values.
    * Files are written completely to a same-directory `.part` file before `ATOMIC_MOVE`; filesystems without atomic move use `REPLACE_EXISTING`, and failures or cancellation clean up the temporary file.

---

## Design Philosophy

The core of this project is a **data fetching and management tool**, rather than a rigidly functioned download application. Its design is based on the following principles:

* **Inversion of Control**: The library itself does not hardcode specific thread scheduling or file I/O. Callers can completely control concurrent behavior and file storage logic by injecting an `ExecutorService` and custom `PathGenerator` strategy interfaces.
* **Process Transparency**: Every intermediate data model in the API call chain (`Album` → `Photo` → `Image`) is accessible. This allows developers to inspect, filter, or customize processing at any stage of the download process.
* **Designed for Integration**: The goal of the library is to serve as a reliable foundational module that can be easily integrated into other large-scale applications, such as Android apps, desktop tools, or backend services.

---

## Installation

The first immutable candidate is `0.1.0-rc.1`. After publication, a normal JVM or Android consumer only needs the core artifact; `picacomic-api` is resolved as a same-version transitive dependency:

```xml
<dependency>
    <groupId>io.github.jukomu</groupId>
    <artifactId>picacomic-core</artifactId>
    <version>0.1.0-rc.1</version>
</dependency>
```

Do not use a `SNAPSHOT`, mutable branch, `mavenLocal()`, or hand-copied JAR as release acceptance evidence. For a source preflight, run `mvn clean verify` at the repository root; that validates the local reactor only and does not claim that Maven Central has published the artifact.

For Android API 24+, use JDK 17, AGP 8.10.1, Gradle 8.11.1, and NIO core-library desugaring:

```kotlin
dependencies {
    implementation("io.github.jukomu:picacomic-core:0.1.0-rc.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}

android {
    compileSdk = 36
    defaultConfig { minSdk = 24; targetSdk = 36 }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

The library returns raw image bytes and does not depend on ImageIO or a desktop WebP decoder; Android applications should use the platform or their own image decoder.

---

## Quick Start

**Note: Unlike some comic platforms, fetching most data from PicaComic requires user login first.**

Below is a complete example of a basic downloader:

```java
package io.github.jukomu.picacomic.sample;

import io.github.jukomu.picacomic.api.client.DownloadResult;
import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.client.PicaRequest;
import io.github.jukomu.picacomic.api.exception.PicaApiException;
import io.github.jukomu.picacomic.api.model.PicaAlbum;
import io.github.jukomu.picacomic.api.model.PicaUserInfo;
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
            // 2. Log in; a successful result always has a complete profile and stable ID
            System.out.println("Logging in...");
            PicaUserInfo user = client.login("your_username_or_email", "your_password");
            System.out.println("Signed in as: " + user.getId());
            
            // 3. Download the specified album
            downloadAlbumWithAllPhotos(client, "album_id_here");
        } catch (PicaApiException e) {
            System.err.println("Pica API failed: " + e.getReason());
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
                    System.err.println("  - Failed to download " + image.getOriginalName() + ": " + error.getMessage())
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
        .proxy("127.0.0.1", 7890) // Optional caller-provided HTTP proxy
        .timeout(Duration.ofSeconds(60)) // Set network timeout to 60 seconds
        .retryTimes(5) // Set maximum retry times
        .imageTimeout(Duration.ofSeconds(60)) // Image read timeout
        .downloadThreadPoolSize(12) // Set internal download thread pool size
        .concurrentPhotoDownloads(3) // Set the number of concurrent chapter downloads
        .concurrentImageDownloads(20) // Number of concurrent image readers (must be positive)
        // .imageQuality(ImageQuality.ORIGINAL) // Set downloaded image quality
        .build();
```

`downloadThreadPoolSize` defaults to 12, `concurrentPhotoDownloads` defaults to 3, and `concurrentImageDownloads` defaults to 20. All three values must be positive and have no library-level hard upper bound. `imageTimeout` defaults to 60 seconds and applies only to image reads. With `loadFromProperties`, use `download.thread.pool.size`, `concurrent.photo.downloads`, `concurrent.image.downloads`, and `image.timeout.seconds` respectively.

The default API host pool contains `picacomic.com`, `picaapi.go2778.com`, `picaapi.acbbb.com`, and `picaapi.picacomic.com`. A `domains(...)` value replaces that pool and is probed before the first API request. Configured hosts are treated as equivalent mirrors; the library does not discover new hosts from network responses.

The proxy applies only to clients created from this configuration and may observe network metadata. The library never enables or switches to a proxy after a failure. API hosts are probed with HEAD before the first API request and periodically thereafter. Image requests are synchronous blank `GET` requests and never inherit API cookies, tokens, signatures, or request bodies; `closeTimeoutMs` controls how long client close waits for its own download tasks.

`fetchImageBytes` is blocking. For cancellation, create `PicaImageRequest request = client.newImageRequest(image)`, call `request.execute()` from an executor owned by your application, and call `request.cancel()`/`request.close()` when the page is destroyed. The returned `byte[]` belongs to the caller after the convenience method returns; the library cannot limit how long the caller retains it.

API operations expose the same single-use synchronous handle. To cancel a paginated or multi-step operation, create a `PicaRequest<T>` and call `execute()` from an executor owned by your application; `cancel()` stops the current and subsequent page, mirror, or login-profile requests. Synchronous convenience methods remain available:

```java
PicaRequest<PicaAlbum> request = client.newAlbumRequest("album_id_here");
try {
    PicaAlbum album = request.execute();
} finally {
    request.close();
}
```

After login, `client.getSession()` returns a process-local snapshot containing no token, cookie, or password. `client.logout()` is local and idempotent, and the client can log in again afterward. A final 401 changes the session to `EXPIRED` and clears credentials; a 403 is not treated as expiry.

Chapter caches use stable `chapterId` identities rather than `(albumId, order)` for full photos. Use `refreshAlbum` or `refreshPhoto` when authoritative data is needed; insertion, reorder, and deletion produce the new stable identity, `STALE_RESOURCE`, or `NOT_FOUND` as appropriate without mixing pages from different chapters.

Image failures are reported as `ImageFetchException`; use `getReason()` for stable values such as `INVALID_SOURCE`, `HTTP_STATUS`, `INVALID_CONTENT`, `TRUNCATED_BODY`, `TIMEOUT`, `CANCELLED`, and `CLIENT_CLOSED`. Credentials in the examples are placeholders only; tests use local TLS fixtures and never access the real service.

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
