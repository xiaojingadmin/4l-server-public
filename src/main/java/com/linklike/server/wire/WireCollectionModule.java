package com.linklike.server.wire;

import com.linklike.server.protocol.WireCodec;
import com.linklike.server.protocol.WireModels;
import com.linklike.server.service.PlayerContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 收藏目录（表情 / 歌曲）—— 对应 Python 版 {@code wire_api.COLLECTION_CATALOGS} 里的两个分支。
 *
 * <p>这两个接口下发的是「客户端里的完整目录」而不是账号收藏：导入的目录行（抓包得到的
 * 全量列表）按已下发资源过滤后，再叠上本账号自己的记录。因此只要目录没导入，接口就是
 * 「账号自己的记录」——与 Python 版同一行为，不会凭空造出未拥有的内容。
 *
 * <p>响应结构与 Python 版一致：<b>不</b>走整包 {@code encode}，只把账号行按元素模型编码，
 * 然后以模型声明的线上字段名直接返回（目录行本身已经是线上形态）。
 */
@Component
public class WireCollectionModule implements WireModule {

    private final WireCodec codec;
    private final WireModels models;
    private final WireServices services;

    public WireCollectionModule(WireCodec codec, WireModels models, WireServices services) {
        this.codec = codec;
        this.models = models;
        this.services = services;
    }

    @Override
    public void register(WireRouter router) {
        router.register("/v1/collection/get_sticker_list", request -> catalog(
                request, "GetStickerListResponse", "StickerInfoList", "Stickers",
                "sticker_collection", "stickers_id", List.of(WireServices.STICKER_ASSETS)));
        router.register("/v1/collection/get_music_list", request -> catalog(
                request, "GetMusicListResponse", "MusicInfoList", "MusicHistory",
                "music_collection", "musics_id", WireServices.MUSIC_ASSETS));
    }

    /**
     * @param model      响应模型（{@code GetStickerListResponse}）
     * @param field      响应里的列表属性（{@code StickerInfoList}）
     * @param storage    账号里存这类记录的状态键（{@code Stickers} / {@code MusicHistory}）
     * @param catalogKey 导入的参考目录键（{@code sticker_collection} / {@code music_collection}）
     * @param identity   两侧共用的标识字段（{@code stickers_id} / {@code musics_id}）
     * @param assets     每条记录需要的资源 label
     */
    private Map<String, Object> catalog(WireRequest request, String model, String field,
                                        String storage, String catalogKey, String identity,
                                        List<String> assets) {
        PlayerContext player = request.requirePlayer();
        WireModels.Field declared = models.field(model, field);
        if (declared == null || declared.elementType() == null) {
            throw new IllegalStateException("协议模型缺少列表属性: " + model + "." + field);
        }
        String element = declared.elementType();

        List<Map<String, Object>> owned = new ArrayList<>();
        for (Map<String, Object> row : player.rowsSnapshot(storage)) {
            owned.add(codec.encode(element, row));
        }
        List<Map<String, Object>> rows = WireServices.overlay(
                services.served(catalogKey, assets), owned, identity);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(declared.wire(), rows);
        return out;
    }
}
