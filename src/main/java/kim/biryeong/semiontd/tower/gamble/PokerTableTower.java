package kim.biryeong.semiontd.tower.gamble;

import java.util.List;
import java.util.UUID;
import kim.biryeong.semiontd.api.SemionTdApi;
import kim.biryeong.semiontd.api.area.AreaEffectOutcome;
import kim.biryeong.semiontd.api.area.AreaVfxSpec;
import kim.biryeong.semiontd.api.area.AreaVfxStyles;
import kim.biryeong.semiontd.api.area.MonsterAreaEffectRequest;
import kim.biryeong.semiontd.config.TowerBalanceRuntime;
import kim.biryeong.semiontd.effect.TimedEffectType;
import kim.biryeong.semiontd.entity.monster.DamageType;
import kim.biryeong.semiontd.entity.tower.SemionTowerEntity;
import kim.biryeong.semiontd.game.GridPosition;
import kim.biryeong.semiontd.game.PlayerLane;
import kim.biryeong.semiontd.game.TeamId;
import kim.biryeong.semiontd.tower.ProductionTower;
import kim.biryeong.semiontd.tower.TowerDataKey;
import kim.biryeong.semiontd.tower.TowerType;
import kim.biryeong.semiontd.tower.TowerUpgradeOption;
import kim.biryeong.semiontd.tower.area.AreaEffectIds;
import kim.biryeong.semiontd.tower.area.TowerAreaDamage;
import kim.biryeong.semiontd.ui.SemionText;
import net.minecraft.resources.ResourceLocation;

public final class PokerTableTower extends ProductionTower {
    private static final TowerDataKey<BetResult> RESULT = TowerDataKey.of(
            ResourceLocation.fromNamespaceAndPath("semion-td", "gamble/poker_result"), BetResult.class);
    private final UUID betToken = UUID.randomUUID();
    // Retain the death source until notification, even if combat already discarded its entity.
    private SemionTowerEntity deathSource;

    public PokerTableTower(TowerType type, UUID owner, TeamId team, int laneId,
                           GridPosition original, GridPosition current) {
        super(type, owner, team, laneId, original, current);
    }

    @Override
    public void onPlaced(PlayerLane lane) {
        syncMaxHealth(effectBaseMaxHealth(), true);
        super.onPlaced(lane);
    }

    @Override
    protected void refreshMaxHealthAfterTypeChange(PlayerLane lane) {
        syncMaxHealth(effectBaseMaxHealth(), false);
    }

    public UUID betToken() {
        return betToken;
    }

    public boolean hasBet() {
        return getData(RESULT).isPresent();
    }

    @Override
    protected void configureEntityAfterSpawn(SemionTowerEntity entity, PlayerLane lane) {
        deathSource = entity;
    }

    @Override
    public void onRemoved(PlayerLane lane) {
        deathSource = null;
        super.onRemoved(lane);
    }

    @Override
    public boolean canUseBasicAttacks() {
        return false;
    }

    @Override
    public boolean canChaseTargets() {
        return false;
    }

    @Override
    public double adjustAttackRange(double baseRange) {
        return 0.0;
    }

    @Override
    public double effectBaseMaxHealth() {
        return super.effectBaseMaxHealth() + getData(RESULT)
                .map(result -> result.hand().weightedScore(result.bet()) / value("healthScoreDivisor"))
                .orElse(0.0);
    }

    @Override
    public boolean meetsUpgradeRequirements(PlayerLane lane, TowerUpgradeOption option) {
        return GamblePoker.UPGRADE_ID.equals(option.id()) && !hasBet() && health() > 0;
    }

    @Override
    public boolean upgradeCostAddsToSaleValue(TowerUpgradeOption option) {
        return false;
    }

    @Override
    public void onUpgradeApplied(PlayerLane lane, TowerUpgradeOption option) {
        if (!meetsUpgradeRequirements(lane, option) || !GamblePoker.validBet(option.mineralCost())) {
            return;
        }
        resolveHand(lane, option.mineralCost(), GamblePoker.draw(lane.arenaWorld().random::nextInt));
    }

    void resolveHand(PlayerLane lane, long bet, GamblePoker.Hand hand) {
        if (hasBet() || !GamblePoker.validBet(bet)) {
            throw new IllegalStateException("A poker table can place one valid bet.");
        }
        setData(RESULT, new BetResult(bet, hand));
        String result = hand.cardsLabel() + " · " + hand.displayName();
        if (hand.destroyed()) {
            message(lane, result + " — 포커 테이블이 파괴됐습니다.");
            // A lost bet permanently removes the tower; it is not a round combat death.
            lane.removeTower(this);
            return;
        }
        syncMaxHealth(effectBaseMaxHealth(), true);
        onStateChanged(lane);
        message(lane, result + " · 점수 " + hand.weightedScore(bet) + " · 최대 체력 "
                + oneDecimal(currentMaxHealth()) + " · 사망 디버프 " + debuffCount() + "개");
        if (hand.weak()) {
            message(lane, "타워가 븅신같이 강화됐습니다");
        }
    }

    public int debuffCount() {
        return getData(RESULT).map(result -> result.hand().debuffCount(
                result.bet(), value("specialScoreThreshold"))).orElse(0);
    }

    public double deathDamageRatio() {
        return value(hasBet() ? "upgradedDeathDamageRatio" : "deathDamageRatio");
    }

    @Override
    public void onDeath(PlayerLane lane) {
        SemionTowerEntity source = deathSource;
        if (source == null) {
            return;
        }
        MonsterAreaEffectRequest request = MonsterAreaEffectRequest.aroundTower(
                AreaEffectIds.tower(this, "poker_death"), source, value("deathRadius"),
                AreaVfxSpec.onTrigger(AreaVfxStyles.SPLASH));
        // Apply strongest-only debuffs before the explosion. Multiple tables refresh duration.
        int count = debuffCount();
        if (count > 0) {
            int ticks = TowerBalanceRuntime.abilityInt(type().id(), "debuffDurationTicks");
            double reduction = value("debuffReduction");
            MonsterAreaEffectRequest debuffRequest = MonsterAreaEffectRequest.aroundTower(
                    request.effectId(), source, request.radius(), AreaVfxSpec.none());
            SemionTdApi.areaEffects().applyToMonsters(debuffRequest, target -> {
                target.applyTimedEffect(TimedEffectType.MONSTER_ATTACK_DAMAGE_REDUCTION, reduction, ticks);
                if (count >= 2) {
                    target.applyTimedEffect(TimedEffectType.MONSTER_ATTACK_SPEED_REDUCTION, reduction, ticks);
                }
                if (count >= 3) {
                    target.applyTimedEffect(TimedEffectType.MONSTER_ARMOR_REDUCTION, reduction, ticks);
                }
                return AreaEffectOutcome.APPLIED;
            });
        }
        TowerAreaDamage.apply(this, source, request,
                target -> currentMaxHealth() * deathDamageRatio(), true,
                (target, damage, killed) -> {}, DamageType.MAGIC);
    }

    @Override
    public List<String> runtimeDetailLines() {
        String deathDamage = "사망 폭발: 최대 체력의 " + percent(deathDamageRatio())
                + " (마법 피해 " + oneDecimal(currentMaxHealth() * deathDamageRatio()) + ")";
        return getData(RESULT).map(result -> List.of(
                "베팅 완료: " + result.bet() + " 다이아 · 재베팅 불가",
                result.hand().cardsLabel() + " · " + result.hand().displayName(),
                "패 점수 " + result.hand().score() + " × 베팅 " + result.bet()
                        + " = " + result.hand().weightedScore(result.bet()),
                "사망 디버프 " + debuffCount() + "개: 공격력 → 공격 속도 → 방어력",
                deathDamage
        )).orElseGet(() -> List.of("아직 베팅하지 않았습니다. 200~1000 다이아로 한 번만 강화할 수 있습니다.", deathDamage));
    }

    private double value(String key) {
        return TowerBalanceRuntime.ability(type().id(), key);
    }

    private void message(PlayerLane lane, String text) {
        var player = lane.arenaWorld().getServer().getPlayerList().getPlayer(ownerPlayer());
        if (player != null) {
            player.sendSystemMessage(SemionText.prefixedPlain(text));
        }
    }

    private record BetResult(long bet, GamblePoker.Hand hand) {
    }
}
