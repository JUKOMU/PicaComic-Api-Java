package io.github.jukomu.picacomic.core;

import io.github.jukomu.picacomic.api.model.PicaAlbum;
import io.github.jukomu.picacomic.api.model.PicaContentPage;
import io.github.jukomu.picacomic.api.model.PicaImage;
import io.github.jukomu.picacomic.api.model.PicaPhoto;
import io.github.jukomu.picacomic.api.model.PicaSessionSnapshot;
import io.github.jukomu.picacomic.api.model.PicaUserInfo;
import io.github.jukomu.picacomic.api.model.SearchQuery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class ModelCopyIsolationTest {

    @Test
    void albumCacheCopiesEveryNestedMutableCollection() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            fixture.server.enqueue(U2TestSupport.probe());
            fixture.server.enqueue(U2TestSupport.albumEps(
                    U2TestSupport.photoSummary("chapter-a", "A", 1)));
            fixture.server.enqueue(U2TestSupport.albumDetailWithNestedModels("album-id", "album"));
            PicaAlbum first = client.getAlbum("album-id");
            first.categories().add("caller-category");
            first.tags().clear();
            first.creator().characters().add("caller-character");
            first.photos().clear();

            PicaAlbum second = client.getAlbum("album-id");
            assertEquals(List.of("category"), second.categories());
            assertEquals(List.of("tag"), second.tags());
            assertEquals(List.of("character"), second.creator().characters());
            assertEquals(1, second.photos().size());
            assertNotSame(first, second);
            assertNotSame(first.creator(), second.creator());
            assertNotSame(first.photos(), second.photos());
        }
    }

    @Test
    void photoCacheCopiesTheImageListAndEachPublicHitIsIndependent() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            fixture.server.enqueue(U2TestSupport.probe());
            fixture.server.enqueue(U2TestSupport.albumEps(
                    U2TestSupport.photoSummary("chapter-a", "A", 1)));
            fixture.server.enqueue(U2TestSupport.albumDetail("album-id", "album"));
            client.getAlbum("album-id");
            fixture.server.enqueue(U2TestSupport.photoPages("chapter-a", "one.png"));

            PicaPhoto first = client.getPhoto("album-id", "chapter-a");
            first.images().add(new PicaImage("caller.png", "", "", "https://image.test/caller.png"));
            PicaPhoto second = client.getPhoto("album-id", "chapter-a");
            assertEquals(1, second.images().size());
            assertNotSame(first, second);
            assertNotSame(first.images(), second.images());
        }
    }

    @Test
    void favoritesCopyNestedAlbumsAndCreatorCharactersAcrossCacheHits() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            fixture.server.enqueue(U2TestSupport.probe());
            fixture.server.enqueue(U2TestSupport.signIn());
            fixture.server.enqueue(U2TestSupport.profile("user-id", "fixture-user"));
            client.login("fixture-user", "fixture-password");
            fixture.server.enqueue(U2TestSupport.contentPageWithNestedModels("favorite"));

            SearchQuery query = new SearchQuery.Builder().build();
            PicaContentPage first = client.getFavorites(query);
            PicaAlbum firstAlbum = first.albums().get(0);
            firstAlbum.categories().clear();
            firstAlbum.creator().characters().clear();
            first.albums().clear();

            PicaContentPage second = client.getFavorites(query);
            assertEquals(List.of("category"), second.albums().get(0).categories());
            assertEquals(List.of("character"), second.albums().get(0).creator().characters());
            assertNotSame(first, second);
            assertNotSame(first.albums(), second.albums());
            assertNotSame(firstAlbum, second.albums().get(0));
        }
    }

    @Test
    void sessionSnapshotsAndLoginResultsDoNotShareUserCollections() throws Exception {
        try (LocalTlsFixture fixture = new LocalTlsFixture();
             DefaultPicaClient client = U2TestSupport.client(fixture)) {
            fixture.server.enqueue(U2TestSupport.probe());
            fixture.server.enqueue(U2TestSupport.signIn());
            fixture.server.enqueue(U2TestSupport.profileWithNestedModels("user-id", "fixture-user"));
            PicaUserInfo loginResult = client.login("fixture-user", "fixture-password");
            loginResult.characters().add("login-only");

            PicaSessionSnapshot first = client.getSession();
            first.user().characters().add("snapshot-only");
            PicaSessionSnapshot second = client.getSession();
            assertEquals(List.of("character"), second.user().characters());
            assertNotSame(first.user(), second.user());
            assertNotSame(loginResult, second.user());
        }
    }

    @Test
    void explicitCopyPreservesMutableListsAndNestedObjectIdentityBoundaries() {
        PicaImage image = new PicaImage("image.png", "path", "https://s2.picacomic.com", null);
        PicaPhoto photo = new PicaPhoto("album", "chapter", "Chapter", "", 2,
                new ArrayList<>(List.of(image)), false);
        PicaUserInfo user = new PicaUserInfo("user", "User", "", "", "", "", "",
                false, 0, 1, new ArrayList<>(List.of("character")), "", image, false, 0);
        PicaAlbum source = new PicaAlbum("album", user, "Album", "", image, "", "",
                new ArrayList<>(List.of("category")), new ArrayList<>(List.of("tag")),
                1, 1, false, "", "", false, false, 0, 0, 0, 0, 0, 0,
                false, false, new ArrayList<>(List.of(photo)));

        PicaAlbum copy = PicaModelCopies.album(source);
        copy.categories().add("copy-category");
        copy.creator().characters().add("copy-character");
        copy.photos().get(0).images().clear();
        assertEquals(List.of("category"), source.categories());
        assertEquals(List.of("character"), source.creator().characters());
        assertEquals(1, source.photos().get(0).images().size());
        assertNotSame(source.creator(), copy.creator());
        assertNotSame(source.photos().get(0), copy.photos().get(0));
        assertNotSame(source.photos().get(0).images(), copy.photos().get(0).images());
        assertSame(image, source.thumb());
        assertNotSame(source.thumb(), copy.thumb());
    }

    @Test
    void albumOrderLookupSortsOnlyAWorkingCopy() {
        PicaPhoto second = new PicaPhoto("album", "second", "second", "", 2,
                new ArrayList<>(), false);
        PicaPhoto first = new PicaPhoto("album", "first", "first", "", 1,
                new ArrayList<>(), false);
        List<PicaPhoto> photos = new ArrayList<>(List.of(second, first));
        PicaAlbum album = new PicaAlbum("album", null, "album", "", null, "", "",
                new ArrayList<>(), new ArrayList<>(), 0, 2, false,
                "", "", false, false, 0, 0, 0, 0, 0, 0, false, false, photos);

        assertEquals("first", album.getPhoto(1).id());
        assertEquals(List.of(second, first), photos);
    }
}
