package io.github.jukomu.picacomic.api.enums;

/**
 * @author JUKOMU
 * @Description: 定义本子分类
 * @Project: PicaComic-Api-Java
 * @Date: 2026/02/20
 */
public enum Category {
    // --- 首页推荐/功能区 ---
    EVERYONE_WATCHING("大家都在看", "大家都在看"),
    EDITOR_RECOMMENDATION("大濕推薦", "大濕推薦"),
    ON_THIS_DAY("那年今天", "那年今天"),
    OFFICIAL_RECOMMENDATION("官方都在看", "官方都在看"),

    // --- 核心漫画分类 ---
    PICA_CHINESE("嗶咔漢化", "未知"),
    FULL_COLOR("全彩", "未知"),
    LONG_STORY("長篇", "未知"),
    DOUJIN("同人", "未知"),
    SHORT_STORY("短篇", "未知"),
    MADOKA("圓神領域", "魔法少女小圓為主題的本子"),
    GRANBLUE_FANTASY("碧藍幻想", "碧藍幻想的本子"),
    CG_ART("CG雜圖", "未知"),
    ENGLISH("英語 ENG", "未知"),
    RAW("生肉", "未知"),
    PURE_LOVE("純愛", "未知"),
    YURI("百合花園", "未知"),
    BL("耽美花園", "未知"),
    FEMBOY("偽娘哲學", "未知"),
    HAREM("後宮閃光", "未知"),
    FUTANARI("扶他樂園", "未知"),
    TANKOUBON("單行本", "未知"),
    SISTER("姐姐系", "未知"),
    LITTLE_SISTER("妹妹系", "未知"),
    SM("SM", "未知"),
    GENDER_BENDER("性轉換", "未知"),
    FOOT_FETISH("足の恋", "未知"),
    WIFE("人妻", "未知"),
    NTR("NTR", "未知"),
    RAPE("強暴", "未知"),
    NON_HUMAN("非人類", "未知"),
    KANCOLLE("艦隊收藏", "未知"),
    LOVE_LIVE("Love Live", "未知"),
    SAO("SAO 刀劍神域", "未知"),
    FATE("Fate", "未知"),
    TOUHOU("東方", "未知"),
    WEBTOON("WEBTOON", "Webtoon 是一種始創於韓國的新概念網路漫畫，由「Web（網路）」及「Cartoon（漫畫、卡通）」組成，只需向上下滑動就能閱讀，不需翻頁，是一種專為電腦及行動裝置而設的漫畫。"),
    INDEX("禁書目錄", "未知"),
    WESTERN("歐美", "歐美"),
    COSPLAY("Cosplay", "未知"),
    HEAVY_FETISH("重口地帶", "未知");

    private final String value;
    private final String description;

    Category(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public String getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根据标题查找枚举
     *
     * @param title 分类标题
     * @return 对应的 Category 枚举，如果未找到则返回 null
     */
    public static Category fromValue(String title) {
        for (Category category : Category.values()) {
            if (category.value.equalsIgnoreCase(title)) {
                return category;
            }
        }
        return null;
    }
}
