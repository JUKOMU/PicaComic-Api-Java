package io.github.jukomu.picacomic.api.model;

import io.github.jukomu.picacomic.api.enums.Category;
import io.github.jukomu.picacomic.api.enums.OrderBy;

import java.util.List;
import java.util.Objects;

/**
 * @author JUKOMU
 * @Description: 封装搜索和分类查询的所有参数
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/20
 */
public final class SearchQuery {
    private final int page;
    private final OrderBy orderBy;
    private final List<Category> categories;
    private final String tag;
    private final String author;
    private final String translator;
    private final String creator;
    private final String keyword;

    private SearchQuery(Builder builder) {
        this.orderBy = Objects.requireNonNull(builder.orderBy, "Order by cannot be null");
        this.categories = builder.categories;
        this.tag = builder.tag;
        this.author = builder.author;
        this.translator = builder.translator;
        this.creator = builder.creator;
        this.keyword = builder.keyword;
        if (builder.page < 1) {
            throw new IllegalArgumentException("Page number must be greater than or equal to 1");
        }
        this.page = builder.page;
    }

    /**
     * 获取搜索标签
     *
     * @return 搜索标签
     */
    public String getTag() {
        return tag;
    }

    /**
     * 获取排序方式
     *
     * @return 排序枚举
     */
    public OrderBy getOrderBy() {
        return orderBy;
    }

    /**
     * 获取主分类
     *
     * @return 主分类枚举
     */
    public List<Category> getCategories() {
        return categories;
    }


    /**
     * 获取要获取的页码
     *
     * @return 页码
     */
    public int getPage() {
        return page;
    }

    /**
     * 获取作者
     *
     * @return 作者
     */
    public String getAuthor() {
        return author;
    }

    /**
     * 获取汉化组
     *
     * @return 汉化组
     */
    public String getTranslator() {
        return translator;
    }

    /**
     * 获取上传者
     *
     * @return 上传者
     */
    public String getCreator() {
        return creator;
    }

    /**
     * 获取搜索关键词
     *
     * @return 搜索关键词
     */
    public String getKeyword() {
        return keyword;
    }

    /**
     * 用于创建 SearchQuery 实例的 Builder
     */
    public static class Builder {
        private int page = 1;
        private OrderBy orderBy = OrderBy.LATEST;
        private List<Category> categories = null;
        private String tag = null;
        private String author = null;
        private String translator = null;
        private String creator = null;
        private String keyword = null;

        /**
         * 设置搜索标签
         *
         * @param tag 搜索标签
         */
        public Builder tag(String tag) {
            this.tag = tag;
            return this;
        }

        /**
         * 设置排序方式
         *
         * @param orderBy 排序枚举
         */
        public Builder orderBy(OrderBy orderBy) {
            this.orderBy = orderBy;
            return this;
        }

        /**
         * 设置主分类
         *
         * @param category 主分类枚举
         */
        public Builder category(Category category) {
            this.categories = List.of(category);
            return this;
        }

        /**
         * 设置主分类
         *
         * @param categories 主分类枚举
         */
        public Builder categories(List<Category> categories) {
            this.categories = categories;
            return this;
        }

        /**
         * 设置要获取的页码
         *
         * @param page 页码，必须 >= 1
         */
        public Builder page(int page) {
            this.page = page;
            return this;
        }

        /**
         * 设置要搜索的作者
         *
         * @param author 作者
         */
        public Builder author(String author) {
            this.author = author;
            return this;
        }

        /**
         * 设置要搜索的汉化组
         *
         * @param translator 汉化组
         */
        public Builder translator(String translator) {
            this.translator = translator;
            return this;
        }

        /**
         * 设置要搜索上传者
         *
         * @param creator 上传者
         */
        public Builder creator(String creator) {
            this.creator = creator;
            return this;
        }

        /**
         * 设置搜索关键词
         *
         * @param keyword 搜索关键词
         */
        public Builder keyword(String keyword) {
            this.keyword = keyword;
            return this;
        }

        /**
         * 构建一个不可变的 SearchQuery 对象
         *
         * @return SearchQuery 实例
         */
        public SearchQuery build() {
            return new SearchQuery(this);
        }
    }
}
