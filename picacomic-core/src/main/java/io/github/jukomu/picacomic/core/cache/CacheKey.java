package io.github.jukomu.picacomic.core.cache;

import java.util.Objects;

/**
 * @author JUKOMU
 * @Description: 唯一标识一个缓存项
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/19
 */
public final class CacheKey {
    private final Class<?> type;
    private final Object id;

    private CacheKey(Class<?> type, Object id) {
        this.type = Objects.requireNonNull(type, "Cache key type cannot be null");
        this.id = Objects.requireNonNull(id, "Cache key id cannot be null");
    }

    public static CacheKey of(Class<?> type, String id) {
        return new CacheKey(type, id);
    }

    /**
     * Creates a key from a structured, value-based identity.
     */
    public static CacheKey of(Class<?> type, Object id) {
        return new CacheKey(type, id);
    }

    public Class<?> type() {
        return type;
    }

    public Object id() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CacheKey cacheKey = (CacheKey) o;
        return type.equals(cacheKey.type) && id.equals(cacheKey.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, id);
    }

    @Override
    public String toString() {
        return "CacheKey{" +
                "type=" + type.getSimpleName() +
                ", id='" + id + '\'' +
                '}';
    }
}
