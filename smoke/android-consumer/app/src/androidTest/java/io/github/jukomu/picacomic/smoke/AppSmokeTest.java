package io.github.jukomu.picacomic.smoke;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.gson.JsonParser;

import io.github.jukomu.picacomic.api.client.IPicaClient;
import io.github.jukomu.picacomic.api.enums.PicaSessionState;
import io.github.jukomu.picacomic.api.model.PicaImage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import okio.ByteString;
import okio.GzipSink;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public final class AppSmokeTest {

    @Test
    public void noCredentialLifecycleAndCancelledRequestAreStable() throws Exception {
        try (IPicaClient client = ConsumerSmoke.newClient()) {
            assertEquals(PicaSessionState.SIGNED_OUT, client.getSession().state());
            ConsumerSmoke.assertCancelledHandleDoesNotNeedNetwork(client);
            client.logout();
            client.logout();
        }
    }

    @Test
    public void imageGzipModelJsonAndNioWorkInTheConsumer() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ConsumerSmoke.exerciseNio(context.getCacheDir().toPath());
        assertEquals("fixture", JsonParser.parseString("{\"name\":\"fixture\"}")
                .getAsJsonObject().get("name").getAsString());

        try (MockWebServer server = new MockWebServer();
             IPicaClient client = ConsumerSmoke.newClient()) {
            server.start();
            Buffer compressed = new Buffer();
            GzipSink gzip = new GzipSink(compressed);
            ByteString bytes = ByteString.encodeUtf8("fixture-image");
            Buffer source = new Buffer().write(bytes);
            gzip.write(source, source.size());
            gzip.close();
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Encoding", "gzip")
                    .setBody(compressed));

            PicaImage image = new PicaImage(
                    "fixture.png", "", "", server.url("/fixture.png").toString());
            byte[] body = client.fetchImageBytes(image);
            assertArrayEquals("fixture-image".getBytes(StandardCharsets.UTF_8), body);
            assertNotNull(image.getOriginalName());
        }
    }
}
