package com.linklike.server.wire;

import com.linklike.server.protocol.J;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 粉丝等级用的主数据目录 —— Python {@code admin/catalog.py} 里 {@code fanlevel} 用到的
 * 那几个方法的本地实现。
 *
 * <p>5.1.0 主数据里随仓库打包了 {@code CardSeries}（{@code ObtainFanLvPt} /
 * {@code EvolutionNFanLvPt} / {@code LimitBreakNFanLvPt}）和 {@code CardDatas}，所以新卡、
 * 进化、界限突破的粉丝点数是真实值；{@code MemberFanLevels} 的等级门槛表没有打包，
 * 此时 {@link #memberFanLevel} 恒为 1 —— 与 Python 在空表上查 {@code MAX(MemberFanLevel)}
 * 得到 {@code max(1, 0)} 的结果一致，等级只升不降。
 */
@Component
public class WireFanCatalog implements WireFanLevel.Catalog {

    private final WireCardMaster master;

    public WireFanCatalog(WireCardMaster master) {
        this.master = master;
        WireFanLevel.install(this);
    }

    @Override
    public int memberFanLevel(long experience) {
        int level = 0;
        for (Map<String, Object> row : J.rowsOr(master.fanLevels())) {
            if (J.longOr(row.get("CumulativeExperience"), Long.MAX_VALUE) <= experience) {
                level = Math.max(level, J.intOr(row.get("MemberFanLevel"), 0));
            }
        }
        return Math.max(1, level);
    }

    @Override
    public long memberFanExperience(int level) {
        long best = 0L;
        for (Map<String, Object> row : J.rowsOr(master.fanLevels())) {
            if (J.intOr(row.get("MemberFanLevel"), 0) <= level) {
                best = Math.max(best, J.longOr(row.get("CumulativeExperience"), 0L));
            }
        }
        return best;
    }

    @Override
    public Map<String, Object> card(Object cardDatasId) {
        return master.card(cardDatasId);
    }

    @Override
    public Map<String, Object> cardSeries(Object cardSeriesId) {
        return master.cardSeries(cardSeriesId);
    }
}
