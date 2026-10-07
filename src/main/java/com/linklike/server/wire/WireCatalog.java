package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireModels;
import com.linklike.server.resource.ClientDefaults;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.RefCatalogStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 导入「客户端包内核实过的静态实体」并对外提供读接口 —— 对应 Python 版
 * {@code wire_catalog.py}。
 *
 * <p>只导入客户端包内的数据，从不导入抓包得到的账号状态；这里也不会编造 master 数据 ID、
 * 拥有关系、购买记录或已完成的玩法进度。
 *
 * <h2>依赖的客户端抽取产物</h2>
 * 三个关键目录（资料模板、角色收藏信息、初始卡）来自客户端抽取脚本，按
 * {@code .gitignore} 不上库。运行时缺失时这里不伪造数据：
 * <ul>
 *   <li>{@link #profileTemplate()} 返回 null，调用方据此返回 501；
 *   <li>{@link #character(int, PlayerContext)} 返回 null（调用方返回 404，与 Python 版
 *       「包里没有这个角色」的语义一致）；
 *   <li>{@link #initialCards()} 返回空表，初始发放只是不补卡，其余字段照常补齐。
 * </ul>
 * 启动时会明确记录哪些文件缺失，不静默降级。
 */
@Component
public class WireCatalog {

    private static final Logger log = LoggerFactory.getLogger(WireCatalog.class);

    /** 资料模板里取出并单独存一份的字段（键名沿用客户端的 snake_case）。 */
    public static final List<String> PROFILE_FIELDS = List.of(
            "profile_icon_parts_info", "profile_card_parts_info", "friend_max_num",
            "music_max_num", "standard_live_max_num");

    private static final String PROFILE_DEFAULTS = "profile_defaults";
    private static final String CHARACTERS = "characters";
    private static final String INITIAL_CARDS = "initial_cards";
    private static final String HOME_PLANS = "home_plans";
    private static final String PETAL_EXCHANGE_RATES = "petal_exchange_rates";

    private final ClientDefaults defaults;
    private final RefCatalogStore catalogs;
    private final WireModels models;

    /** 花瓣兑换表与 Stage 卡组槽位是纯静态文件，读一次缓存（Python 版每次读文件）。 */
    private volatile List<Map<String, Object>> petalRatesCache;
    private volatile List<Map<String, Object>> stageDeckSlotsCache;

    public WireCatalog(ClientDefaults defaults, RefCatalogStore catalogs, WireModels models) {
        this.defaults = defaults;
        this.catalogs = catalogs;
        this.models = models;
    }

    /**
     * 启动时导入客户端包内的静态实体 —— 对应 Python 版 {@code server.load_users} 里的
     * {@code wire_api.wire_catalog.seed(_db)}。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedAtStartup() {
        defaults.logAvailability();
        seed();
    }

    public void seed() {
        seedProfileDefaults();
        seedCharacters();
        seedInitialCards();
        seedQuestStages();
        log.info("参考目录：资料模板 {}，角色 {}，初始卡 {}，标准关卡 {}",
                catalogs.count(PROFILE_DEFAULTS), catalogs.count(CHARACTERS),
                catalogs.count(INITIAL_CARDS), catalogs.count("quest_stages"));
    }

    // ------------------------------------------------------------------
    // 导入
    // ------------------------------------------------------------------

    private void seedProfileDefaults() {
        if (catalogs.has(PROFILE_DEFAULTS, "default")) {
            return;
        }
        Map<String, Object> source = defaults
                .object(ClientDefaults.EXTRACTED_PREFIX + "getProfileInfo")
                .map(doc -> J.nodeOr(doc.get("profile_info")))
                .orElse(null);
        if (source == null) {
            log.warn("缺 client_defaults/base_res_content_getProfileInfo.json，"
                    + "资料模板未导入：home/get_home、profile/get_info 等接口会返回 501");
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        for (String key : PROFILE_FIELDS) {
            row.put(key, source.get(key));
        }
        catalogs.put(PROFILE_DEFAULTS, "default", row);
    }

    private void seedCharacters() {
        for (String name : defaults.fileNames(ClientDefaults.EXTRACTED_PREFIX + "getCharacterInfo_")) {
            String ident = name.substring(name.lastIndexOf('_') + 1);
            if (ident.isEmpty() || !ident.chars().allMatch(Character::isDigit)) {
                // 抽取来源标记（*.source.json）不是角色实体，跳过。
                continue;
            }
            if (catalogs.has(CHARACTERS, ident)) {
                continue;
            }
            Map<String, Object> info = defaults.object(name)
                    .map(doc -> J.nodeOr(doc.get("collection_character_info")))
                    .orElse(null);
            if (info == null) {
                continue;
            }
            if (!ident.equals(String.valueOf(info.get("character_id")))) {
                throw new IllegalStateException("客户端包内角色 ID 不一致: " + name);
            }
            // 包里的 is_opened 是抽样状态，账号自己的解锁情况稍后覆盖。
            for (Map<String, Object> card : J.rowsOr(info.get("card_list"))) {
                for (Map<String, Object> voice : J.rowsOr(card.get("voice_list"))) {
                    voice.remove("is_opened");
                }
                for (Map<String, Object> movie : J.rowsOr(card.get("movie_list"))) {
                    movie.remove("is_opened");
                }
            }
            catalogs.put(CHARACTERS, ident, info, true);
        }
        if (catalogs.count(CHARACTERS) == 0) {
            log.warn("缺 client_defaults/base_res_content_getCharacterInfo_*.json，"
                    + "收藏角色信息未导入：collection/get_character_info 会返回 404");
        }
    }

    /**
     * 打包的 {@code getCardList} fixture -> {@code initial_cards}：每位角色一张
     * style level 1 的基础服装卡，键为主数据 {@code card_datas_id}。
     *
     * <p>fixture 里的实例 ID（{@code d_card_datas_id}）是抽样状态，会被丢掉；
     * 账号由 {@link WireInitial#grant} 生成自己的实例。
     */
    public void seedInitialCards() {
        if (catalogs.count(INITIAL_CARDS) > 0) {
            return;
        }
        List<Map<String, Object>> fixture = defaults
                .object(ClientDefaults.EXTRACTED_PREFIX + "getCardList")
                .map(doc -> J.rowsOr(doc.get("user_card_data_list")))
                .orElse(null);
        if (fixture == null) {
            log.warn("缺 client_defaults/base_res_content_getCardList.json，"
                    + "初始卡目录未导入：新注册账号不会收到初始卡组（WireInitial 会跳过发卡）");
            return;
        }
        for (Map<String, Object> card : fixture) {
            if (!(card.get("card_datas_id") instanceof Number)
                    || !(card.get("character_id") instanceof Number)) {
                throw new IllegalStateException("客户端包内卡片 fixture 缺少主数据标识");
            }
            Map<String, Object> row = new LinkedHashMap<>(card);
            row.remove("d_card_datas_id");
            catalogs.put(INITIAL_CARDS, card.get("card_datas_id"), row, true);
        }
    }

    /** 5.1.0 客户端主数据 -> Stage Live 各套目录（标准 / 日常 / 学习 / Grade）。 */
    private void seedQuestStages() {
        seedRows("standard_quest_stages", "quest_stages", "stage_id");
        seedRows("daily_quest_stages", "daily_quest_stages", "stage_id");
        seedRows("learning_live_catalog", "learning_live_catalog", "learning_live_series_id");
        seedRows("grade_live_catalog", "grade_live_catalog", "quest_id");
        seedRows("grade_season_catalog", "grade_season_catalog", "grade_quest_season_id");
        seedRows("grade_quest_stages", "grade_quest_stages", "stage_id");
        seedRows("grade_quest_squares", "grade_quest_squares", "grade_quest_square_id");
    }

    private void seedRows(String fileName, String catalogKey, String identityKey) {
        List<Map<String, Object>> rows = defaults.rows(fileName);
        if (rows.isEmpty()) {
            return;
        }
        catalogs.seedBatch(catalogKey, rows, row -> row.get(identityKey));
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 新账号的资料模板（图标 / 名片部件、好友上限……）。
     *
     * <p>客户端抽取产物缺失时返回 null —— 调用方必须返回 501，不能拿空对象糊过去。
     */
    public Map<String, Object> profileTemplate() {
        return catalogs.get(PROFILE_DEFAULTS, "default");
    }

    /** 收藏角色信息，叠加本账号的语音 / 影片解锁状态；包里没有这个角色时返回 null。 */
    public Map<String, Object> character(int characterId, PlayerContext player) {
        Map<String, Object> stored = catalogs.get(CHARACTERS, characterId);
        if (stored == null) {
            return null;
        }
        Map<String, Object> info = copy(stored);
        List<Object> unlockedVoices = player.raw("UnlockedVoices") instanceof List<?> list
                ? new ArrayList<>(list) : new ArrayList<>();
        List<Object> unlockedMovies = player.raw("UnlockedMovies") instanceof List<?> list
                ? new ArrayList<>(list) : new ArrayList<>();
        for (Map<String, Object> card : J.rowsOr(info.get("card_list"))) {
            for (Map<String, Object> voice : J.rowsOr(card.get("voice_list"))) {
                voice.put("is_opened", containsId(unlockedVoices, voice.get("voices_id")));
            }
            for (Map<String, Object> movie : J.rowsOr(card.get("movie_list"))) {
                movie.put("is_opened", containsId(unlockedMovies, movie.get("movies_id")));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collection_character_info", info);
        return out;
    }

    /** 初始卡目录：主数据 {@code card_datas_id} -> wire 拼写的 {@code UserCardData} 行。 */
    public List<Map<String, Object>> initialCards() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : catalogs.list(INITIAL_CARDS)) {
            out.add(models.toProperties("UserCardData", row));
        }
        return out;
    }

    /**
     * 可玩 Stage 卡组的槽位（主数据里 {@code EditOnlyDisplay==0} 的世代）。
     *
     * <p>对应 Python 版 {@code wire_catalog.stage_deck_slots()}。
     */
    public List<Map<String, Object>> stageDeckSlots() {
        List<Map<String, Object>> cached = stageDeckSlotsCache;
        if (cached == null) {
            cached = List.copyOf(defaults.rows("stage_deck_slots"));
            stageDeckSlotsCache = cached;
        }
        return new ArrayList<>(cached);
    }

    /** 5.1.0 {@code PetalExchangeRates} 主数据。 */
    public List<Map<String, Object>> petalExchangeRates() {
        List<Map<String, Object>> cached = petalRatesCache;
        if (cached == null) {
            cached = List.copyOf(defaults.rows("petal_exchange_rates"));
            petalRatesCache = cached;
        }
        return new ArrayList<>(cached);
    }

    /**
     * 首页运营位（{@code home_plans} 目录）。
     *
     * <p>这个目录由运营导入，不是客户端包内数据，因此默认是空的（与 Python 版一致）。
     */
    public List<Map<String, Object>> homePlans() {
        return catalogs.list(HOME_PLANS);
    }

    /** 客户端抽取产物是否可用（资料模板 + 初始卡）。 */
    public boolean fixturesAvailable() {
        return profileTemplate() != null;
    }

    /** 已导入的收藏角色数量；为 0 说明本机没有客户端抽取的角色信息。 */
    public long characterCount() {
        return catalogs.count(CHARACTERS);
    }

    /** 已导入的初始卡数量；为 0 说明本机没有客户端抽取的 getCardList。 */
    public long initialCardCount() {
        return catalogs.count(INITIAL_CARDS);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static boolean containsId(List<Object> ids, Object value) {
        for (Object candidate : ids) {
            if (candidate != null && String.valueOf(candidate).equals(String.valueOf(value))) {
                return true;
            }
        }
        return false;
    }

    /** 深拷贝（Python 的 {@code copy.deepcopy}），避免模板被账号状态改脏。 */
    private static Map<String, Object> copy(Map<String, Object> value) {
        Map<String, Object> out = new LinkedHashMap<>();
        value.forEach((key, item) -> out.put(key, deepCopy(item)));
        return out;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), deepCopy(item)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(deepCopy(item));
            }
            return out;
        }
        return value;
    }
}
