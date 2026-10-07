package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import com.linklike.server.protocol.WireCodec;
import com.linklike.server.protocol.WireError;
import com.linklike.server.service.ConnectCode;
import com.linklike.server.service.PlayerContext;
import com.linklike.server.service.PlayerStore;
import com.linklike.server.service.RefCatalogStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code wire_api.py} 里 {@code dispatch} 内联分支的移植 —— 注册、登录、账号连携、教程、
 * 个人资料、收藏与首页。
 *
 * <p>Python 版把这些分支直接写在 {@code wire_api.dispatch} 里，没有单独的模块文件；
 * 这里保持同样的分组，集中放在一个 {@link WireModule} 中，其余 {@code wire_*.py} 模块
 * （{@code wire_services}、{@code wire_cards}、{@code wire_live}……）移植前其路径由
 * {@link RouteSnapshot} 显式注册为 501。
 *
 * <p>字段名与错误文案逐条对齐 Python 版，包括「未编码、直接返回 snake_case 字面量」的
 * 少数分支（{@code register/getterms}、{@code register/setuserdata}、
 * {@code account/connect}）。
 */
@Component
public class WireApiModule implements WireModule {

    /** 客户端 {@code UserLoginRequest._version} 之外，Python 版还读这个键名。 */
    private static final String VERSION_KEY = "version";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WireCodec codec;
    private final WireCatalog catalog;
    private final WireState state;
    private final WireLoginBonus loginBonus;
    private final RefCatalogStore catalogs;

    public WireApiModule(WireCodec codec, WireCatalog catalog, WireState state,
                         WireLoginBonus loginBonus, RefCatalogStore catalogs) {
        this.codec = codec;
        this.catalog = catalog;
        this.state = state;
        this.loginBonus = loginBonus;
        this.catalogs = catalogs;
    }

    @Override
    public void register(WireRouter router) {
        router.register("/v1/user/login", this::login);
        router.register("/v1/account/connect", this::connect);
        router.register("/v1/register/getterms", this::getTerms);
        router.register("/v1/register/approveterms", this::approveTerms);
        router.register("/v1/register/setapproveterms", this::approveTerms);
        router.register("/v1/register/setnewuser", this::setNewUser);
        router.register("/v1/register/setuserdata", this::setNewUser);

        router.register("/v1/collection/get_character_info", this::characterInfo);

        router.register("/v1/user/card/get_list",
                readModel("CardGetListResponse", "UserCardDataList", "Cards"));
        router.register("/v1/user/items/get_list",
                readModel("UserItemGetListResponse", "ItemCategoryList", "Items"));
        router.register("/v1/profile/get_my_design_card_list",
                readModel("ProfileGetMyDesignCardListResponse", "MyDesignList", "CardDesigns"));
        router.register("/v1/profile/get_my_design_icon_list",
                readModel("ProfileGetMyDesignIconListResponse", "MyDesignList", "IconDesigns"));
        router.register("/v1/user/card/get_detail", this::cardDetail);

        router.register("/v1/profile/get_profile_icon", this::profileIcon);
        router.register("/v1/profile/get_profile_card", this::profileCard);
        router.register("/v1/profile/set_name", this::setName);
        router.register("/v1/profile/set_comment", this::setComment);
        router.register("/v1/profile/set_my_design_card", this::setMyDesignCard);
        router.register("/v1/profile/set_my_design_icon", this::setMyDesignIcon);
        router.register("/v1/profile/get_info", this::profileInfo);
        router.register("/v1/profile/get_fan_level_info", this::fanLevelInfo);
        router.register("/v1/follow/set_friend_card", this::setFriendCard);

        router.register("/v1/home/gethome", this::home);
        router.register("/v1/home/get_custom_setting", this::customSetting);
        router.register("/v1/home/get_login_bonus", this::getLoginBonus);

        router.register("/v1/tutorial/set_step", this::setTutorialStep);
        router.register("/v1/user/set_simple_tutorial_finish", this::setSimpleTutorialFinish);
    }

    // ------------------------------------------------------------------
    // 登录 / 注册 / 连携
    // ------------------------------------------------------------------

    /**
     * {@code /v1/user/login}：老账号按 {@code player_id} 或设备标识认领，新客户端拿到临时访客。
     *
     * <p>重装后缓存里的 {@code player_id} 会失效，而设备标识仍绑在本地账号上，所以设备
     * 标识是第二判据；两者都对不上时只有 {@code LoginAlias} 还能救回账号（官方连携会在
     * 手机上留下另一个 player_id，别名就是「绑定」本身），此时顺便把设备改绑过来。
     */
    private Map<String, Object> login(WireRequest request) {
        Map<String, Object> body = request.body();
        Object rawPid = body.containsKey("player_id") ? body.get("player_id") : "";
        Object rawDevice = body.containsKey("device_specific_id") ? body.get("device_specific_id") : "";
        Object rawVersion = body.containsKey(VERSION_KEY) ? body.get(VERSION_KEY) : 1;
        if (!(rawPid instanceof String pid) || !(rawDevice instanceof String device)
                || !isStrictInt(rawVersion)) {
            throw WireError.badRequest("Invalid login field types");
        }

        PlayerStore store = request.store();
        PlayerContext actor;
        if (!pid.isEmpty() || !device.isEmpty()) {
            PlayerContext target = pid.isEmpty() ? null : store.loadById(pid);
            if (target == null) {
                target = store.loadByDevice(device);
            }
            if (target == null || device.isEmpty()) {
                throw new WireError(401, "Invalid local player credentials");
            }
            String expected = target.entity().deviceSpecificId == null
                    ? "" : target.entity().deviceSpecificId;
            boolean sameDevice = expected.length() == device.length()
                    && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                            device.getBytes(StandardCharsets.UTF_8));
            if (!sameDevice) {
                if (!pid.equals(target.entity().loginAlias)) {
                    throw new WireError(401, "Invalid local player credentials");
                }
                target.entity().deviceSpecificId = device;
                store.save(target);
            }
            actor = target;
            rejectBanned(actor);
            // 初始发放早于这个账号注册时，下次登录补上（只补一次，见 WireInitial）。
            if (WireInitial.grant(actor, catalog)) {
                store.save(actor);
            }
        } else if (request.player() == null) {
            actor = store.createGuest("Player", null);
            actor.entity().temporary = true;
        } else {
            actor = request.player();
        }
        actor.entity().lastLogin = Instant.now().getEpochSecond();
        store.issueSession(actor, store.genToken());
        return loginResponse(actor, actor.entity().temporary ? 0 : 1, request);
    }

    /**
     * {@code /v1/account/connect}（データ引き継ぎ）：用 {@code player_id} + {@code id_token}
     * （用户中心设置的本地连携码）认领本地账号；认领后换发新的设备 ID，与原设备解绑。
     * 不带 {@code player_id} 的第三方关联登录仍按匿名会话创建账号。
     */
    private Map<String, Object> connect(WireRequest request) {
        PlayerStore store = request.store();
        Object requested = request.arg("player_id");
        PlayerContext actor = request.player();
        if (requested instanceof String pid && !pid.isEmpty()) {
            PlayerContext target = store.loadById(pid);
            if (target == null) {
                throw new WireError(404, "玩家 ID 不存在");
            }
            String secret = target.entity().connectSecret;
            if (secret == null || secret.isEmpty()) {
                throw new WireError(403, "该账号尚未在用户中心设置连携码");
            }
            Object code = request.arg("id_token");
            if (!(code instanceof String codeText) || !ConnectCode.verify(secret, codeText)) {
                throw new WireError(401, "连携码不正确");
            }
            rejectBanned(target);
            target.entity().deviceSpecificId = UUID.randomUUID().toString();
            target.entity().temporary = false;
            actor = target;
            store.save(actor);
        }
        if (actor == null) {
            actor = store.createGuest("Player", null);
            actor.entity().temporary = true;
        }
        store.issueSession(actor, store.genToken());
        // 这个分支的响应体在 Python 版里就是 snake_case 字面量，没有走 encode。
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("player_id", actor.id());
        out.put("device_specific_id", actor.entity().deviceSpecificId);
        out.put("session_token", actor.entity().sessionToken);
        out.put("player_name", actor.entity().playerName == null ? "" : actor.entity().playerName);
        out.put("player_level", actor.entity().playerLevel);
        return out;
    }

    /** {@code /v1/register/getterms}：唯一一个不需要会话的 {@code /v1} 分支。 */
    private Map<String, Object> getTerms(WireRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("terms", "Local development server. Test data only; "
                + "no purchases or official account login.");
        return out;
    }

    private Map<String, Object> approveTerms(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        boolean temporary = player.entity().temporary;
        player.entity().temporary = false;
        request.store().save(player);
        return encode("RegisterApproveTermsResponse", J.map(
                "Type", temporary ? 2 : 1,
                "PlayerId", player.id(),
                "DeviceSpecificId", player.entity().deviceSpecificId,
                "SessionToken", player.entity().sessionToken));
    }

    private Map<String, Object> setNewUser(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Object rawName = request.arg("name");
        if (!(rawName instanceof String name) || name.strip().isEmpty()
                || name.strip().length() > 8) {
            throw WireError.badRequest(
                    "A non-empty name of at most 8 characters is required");
        }
        player.entity().playerName = name.strip();
        player.entity().registrationComplete = true;
        player.entity().temporary = false;
        WireInitial.grant(player, catalog);
        request.store().save(player);
        if (request.path().endsWith("setuserdata")) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("result", true);
            return out;
        }
        return encode("RegisterSetNewUserResponse", J.map(
                "PlayerId", player.id(),
                "DeviceSpecificId", player.entity().deviceSpecificId,
                "SessionToken", player.entity().sessionToken));
    }

    /**
     * 登录响应 —— 对应 Python 版 {@code wire_api.login_response}。
     *
     * <p>{@code IsLoginBonusReceive} 决定客户端进首页后要不要弹登录奖励板。
     */
    private Map<String, Object> loginResponse(PlayerContext player, int type,
                                              WireRequest request) {
        List<Map<String, Object>> tutorials = new ArrayList<>();
        for (Object ident : player.scalarsOrEmpty("TutorialSteps")) {
            tutorials.add(J.map("TutorialId", ident, "IsComplete", true));
        }
        return encode("UserLoginResponse", J.map(
                "Type", type,
                "SessionToken", player.entity().sessionToken,
                "IsTutorial", !player.entity().registrationComplete,
                "IsTermUpdate", false,
                "IsLoginBonusReceive", loginBonus.hasPending(player, Instant.now()),
                "PushDeviceToken", "",
                "SiscaProductIdList", storeProducts("sisca"),
                "MembershipProductIdList", storeProducts("membership"),
                "ItemStoreProductIdList", storeProducts("item_store"),
                "TutorialsStatusList", tutorials));
    }

    /** 某类商店 SKU 列表（导入的 {@code store_products} 目录里 {@code kind} 匹配的行）。 */
    private List<Object> storeProducts(String kind) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> row : catalogs.list("store_products")) {
            if (kind.equals(row.get("kind")) && row.get("product_id") != null) {
                out.add(row.get("product_id"));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 收藏 / 用户数据读取
    // ------------------------------------------------------------------

    private Map<String, Object> characterInfo(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Object rawId = request.arg("character_id");
        if (!isStrictInt(rawId)) {
            throw WireError.badRequest("Invalid character_id");
        }
        int characterId = ((Number) rawId).intValue();
        if (characterId <= 0 || characterId >= 100_000) {
            throw WireError.badRequest("Invalid character_id");
        }
        // Python 查不到角色时统一 404「Character not present in packaged client data」，
        // 包括本机完全没有客户端抽取产物的情形。这里不改成 501：同一个路径不应该因为
        // 「没做过抽取」和「抽取了但没这个角色」给出两种状态码。
        Map<String, Object> result = catalog.character(characterId, player);
        if (result == null) {
            throw new WireError(404, "Character not present in packaged client data");
        }
        return result;
    }

    /** {@code READ_MODELS}：把账号状态里的一个集合直接编码下发。 */
    private WireHandler readModel(String model, String field, String storage) {
        return request -> {
            request.requirePlayer();
            return encode(model, J.map(field, request.player().rows(storage)));
        };
    }

    private Map<String, Object> cardDetail(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        return encode("GetDetailResponse", J.map("UserCardData", ownedCard(player, request.body())));
    }

    /** 对应 Python 版 {@code wire_api._owned_card}。 */
    private static Map<String, Object> ownedCard(PlayerContext player, Map<String, Object> body) {
        Object rawIdent = body.get("d_card_datas_id");
        if (!(rawIdent instanceof String ident) || ident.isEmpty() || ident.length() > 128) {
            throw WireError.badRequest("d_card_datas_id must be a non-empty string");
        }
        for (Map<String, Object> card : player.rows("Cards")) {
            if (ident.equals(card.get("DCardDatasId"))) {
                return card;
            }
        }
        throw new WireError(404, "Local card not found");
    }

    // ------------------------------------------------------------------
    // 个人资料
    // ------------------------------------------------------------------

    private Map<String, Object> profileIcon(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> template = requireProfileTemplate();
        return encode("ProfileGetProfileIconResponse", J.map("ProfileIconPartsInfo",
                valueOrTemplate(player, "ProfileIconPartsInfo", template, "profile_icon_parts_info")));
    }

    private Map<String, Object> profileCard(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Map<String, Object> template = requireProfileTemplate();
        return encode("ProfileGetProfileCardResponse", J.map("ProfileCardPartsInfo",
                valueOrTemplate(player, "ProfileCardPartsInfo", template, "profile_card_parts_info")));
    }

    private Map<String, Object> setName(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Object rawName = request.arg("name");
        if (!(rawName instanceof String name) || name.strip().isEmpty()
                || name.strip().length() > 8) {
            throw WireError.badRequest(
                    "A non-empty name of at most 8 characters is required");
        }
        player.entity().playerName = name.strip();
        request.store().save(player);
        return encode("ProfileSetNameResponse", J.map("Result", true));
    }

    private Map<String, Object> setComment(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Object rawComment = request.arg("comment");
        if (!(rawComment instanceof String comment) || comment.length() > 100) {
            throw WireError.badRequest("comment must be a string of at most 100 characters");
        }
        player.entity().comment = comment;
        request.store().save(player);
        return encode("ProfileSetCommentResponse", J.map("Result", true));
    }

    /**
     * 「マイデザイン」名片与图标共用同一套逻辑，只有状态键与 {@code MyDesignType} 不同
     * （对应 Python 版 {@code set_my_design_card} / {@code set_my_design_icon} 两段近乎
     * 重复的代码）。
     *
     * @param designsKey    账号里存设计列表的状态键（{@code CardDesigns} / {@code IconDesigns}）
     * @param partsKey      账号里存当前部件的状态键
     * @param partsArgument 请求体里的部件字段名
     * @param designType    {@code MyDesignType}（名片 1，图标 0）
     */
    private Map<String, Object> setMyDesign(WireRequest request, String designsKey,
                                            String partsKey, String partsArgument,
                                            int designType, String responseModel) {
        PlayerContext player = request.requirePlayer();
        Object rawParts = request.arg(partsArgument);
        Object rawName = request.arg("my_design_name");
        Object rawDesignId = request.body().containsKey("d_profile_my_designs_id")
                ? request.arg("d_profile_my_designs_id") : "";
        if (!(rawParts instanceof String parts) || !(rawName instanceof String name)) {
            throw WireError.badRequest(partsArgument + " and my_design_name must be strings");
        }
        if (name.strip().isEmpty() || name.strip().length() > 20) {
            throw WireError.badRequest("my_design_name must be 1 to 20 characters");
        }
        if (!(rawDesignId instanceof String designId)) {
            throw WireError.badRequest("d_profile_my_designs_id must be a string");
        }
        List<Map<String, Object>> designs = player.rows(designsKey);
        Map<String, Object> existing = null;
        if (!designId.isEmpty()) {
            for (Map<String, Object> row : designs) {
                if (designId.equals(row.get("DProfileMyDesignsId"))) {
                    existing = row;
                    break;
                }
            }
            if (existing == null) {
                throw new WireError(404, "My design not found");
            }
        } else {
            designId = randomHex(16);
            existing = J.map("DProfileMyDesignsId", designId, "MyDesignType", designType);
            designs.add(existing);
        }
        existing.put("MyDesignPartsInfo", parts);
        existing.put("MyDesignName", name.strip());
        player.put(partsKey, parts);
        request.store().save(player);
        return encode(responseModel, J.map("Result", true));
    }

    private Map<String, Object> setMyDesignCard(WireRequest request) {
        return setMyDesign(request, "CardDesigns", "ProfileCardPartsInfo",
                "profile_card_parts_info", 1, "ProfileSetMyDesignCardResponse");
    }

    private Map<String, Object> setMyDesignIcon(WireRequest request) {
        return setMyDesign(request, "IconDesigns", "ProfileIconPartsInfo",
                "profile_icon_parts_info", 0, "ProfileSetMyDesignIconResponse");
    }

    private Map<String, Object> profileInfo(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Object requested = request.arg("player_id");
        PlayerContext target = truthy(requested)
                ? request.store().loadById(String.valueOf(requested))
                : player;
        if (target == null) {
            throw new WireError(404, "Local player not found");
        }
        requireProfileTemplate();
        List<Object> followed = WireFriends.followIds(player);
        return encode("ProfileGetInfoResponse", J.map(
                "ProfileInfo", state.profile(target),
                "IsOwn", target.id().equals(player.id()),
                "IsMute", WireFriends.containsId(idsOf(player.raw("muted_players")), target.id()),
                "IsReport", WireFriends.containsId(idsOf(player.raw("reported_players")), target.id()),
                "FriendRequestStatus", WireFriends.friendRequestStatus(player, target),
                "CircleInviteStatus", 0,
                "FollowStatus", WireFriends.containsId(followed, target.id()) ? 1 : 0));
    }

    private Map<String, Object> fanLevelInfo(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Object requested = request.arg("player_id");
        PlayerContext target = truthy(requested)
                ? request.store().loadById(String.valueOf(requested))
                : player;
        if (target == null) {
            throw new WireError(404, "Local player not found");
        }
        return encode("ProfileGetFanLevelInfoResponse", J.map(
                "FanLevelInfoList", target.rows("FanLevelDetails"),
                "SeasonId", target.intOf("SeasonId", 0),
                "SeasonFanlevelPointStock", target.intOf("SeasonFanlevelPointStock", 0),
                "SeasonFanlevelPointStockLimit", target.intOf("SeasonFanlevelPointStockLimit", 0)));
    }

    private Map<String, Object> setFriendCard(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Object rawSchool = request.body().containsKey("school_idol_stage_card_id")
                ? request.arg("school_idol_stage_card_id") : "";
        Object rawRhythm = request.body().containsKey("rhythm_game_card_id")
                ? request.arg("rhythm_game_card_id") : "";
        if (!(rawSchool instanceof String school) || !(rawRhythm instanceof String rhythm)) {
            throw WireError.badRequest("card identifiers must be strings");
        }
        if (school.length() > 128 || rhythm.length() > 128) {
            throw WireError.badRequest("card identifiers are too long");
        }
        requireOwnedCard(player, school);
        requireOwnedCard(player, rhythm);
        player.put("FriendCard", J.map(
                "SchoolIdolStageCardId", school,
                "RhythmGameCardId", rhythm));
        request.store().save(player);
        return encode("FollowSetFriendCardResponse", J.map("Result", true));
    }

    private static void requireOwnedCard(PlayerContext player, String ident) {
        if (ident.isEmpty()) {
            return;
        }
        for (Map<String, Object> card : player.rows("Cards")) {
            if (ident.equals(card.get("DCardDatasId"))) {
                return;
            }
        }
        throw new WireError(404, "Local card not found");
    }

    // ------------------------------------------------------------------
    // 首页 / 教程
    // ------------------------------------------------------------------

    private Map<String, Object> home(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        requireProfileTemplate();
        return encode("HomeGetHomeResponse", state.home(player));
    }

    private Map<String, Object> customSetting(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        requireProfileTemplate();
        return encode("HomeGetCustomSettingResponse", J.map(
                "StickerInfoList", player.rows("HomeStickers"),
                "UserCardDataList", player.rows("HomeCards"),
                "UserIconFrame", player.rows("IconFrames"),
                "ProfileInfo", state.profile(player)));
    }

    /**
     * {@code /v1/home/get_login_bonus}：奖励板来自上游抓包，唯一的本地写入是今天的盖章。
     * 奖励只展示，不发放到背包 —— 对应 Python 版 {@code wire_login_bonus}。
     */
    private Map<String, Object> getLoginBonus(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        if (loginBonus.sync(player, Instant.now())) {
            request.store().save(player);
        }
        return encode("HomeGetLoginBonusResponse", loginBonus.response(player));
    }

    private Map<String, Object> setTutorialStep(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Object rawId = request.arg("tutorial_id");
        if (!isStrictInt(rawId) || ((Number) rawId).intValue() <= 0) {
            throw WireError.badRequest("tutorial_id must be a positive integer");
        }
        int tutorialId = ((Number) rawId).intValue();
        // 只记本地观察到的教程标记：不完成玩法、不发奖励、不建卡、不改进度。
        List<Object> steps = player.scalarList("TutorialSteps");
        if (!steps.contains(tutorialId)) {
            steps.add(tutorialId);
            request.store().save(player);
        }
        return encode("TutorialSetStepResponse", J.map("Result", true));
    }

    private Map<String, Object> setSimpleTutorialFinish(WireRequest request) {
        PlayerContext player = request.requirePlayer();
        Object rawId = request.arg("contents_id");
        if (!isStrictInt(rawId) || ((Number) rawId).intValue() < 0) {
            throw WireError.badRequest("contents_id must be a non-negative integer");
        }
        int contentsId = ((Number) rawId).intValue();
        List<Object> finished = player.scalarList("FinishedTutorials");
        if (!finished.contains(contentsId)) {
            finished.add(contentsId);
            request.store().save(player);
        }
        return new LinkedHashMap<>();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Map<String, Object> encode(String model, Map<String, Object> data) {
        return codec.encode(model, data);
    }

    private Map<String, Object> requireProfileTemplate() {
        Map<String, Object> template = catalog.profileTemplate();
        if (template == null) {
            // 客户端抽取产物缺失：这几个接口需要资料模板才能给出正确形状，不返回空对象。
            throw WireError.unimplemented();
        }
        return template;
    }

    /** Python 的 {@code player.get(key, template[key])}：键存在（哪怕为 null）就用账号值。 */
    private static Object valueOrTemplate(PlayerContext player, String key,
                                          Map<String, Object> template, String templateKey) {
        return player.has(key) ? player.raw(key) : template.get(templateKey);
    }

    private static void rejectBanned(PlayerContext player) {
        if (player.entity().banned) {
            String reason = player.entity().banReason;
            throw WireError.forbidden("このアカウントは利用停止中です。"
                    + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")"));
        }
    }

    private static List<Object> idsOf(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
    }

    /** Python 的 {@code type(x) is int}：布尔、浮点、字符串都不算整数。 */
    private static boolean isStrictInt(Object value) {
        return value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte
                || value instanceof java.math.BigInteger;
    }

    /**
     * Python 的 {@code x in list} 语义之外的「真值」判断：{@code None}、{@code ""}、
     * {@code 0}、{@code False} 都算假（{@code wire_api} 里用它决定「是否指定了 player_id」）。
     */
    private static boolean truthy(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof java.util.Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return java.util.HexFormat.of().formatHex(buffer);
    }
}
