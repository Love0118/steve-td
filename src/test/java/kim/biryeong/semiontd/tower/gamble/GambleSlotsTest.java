package kim.biryeong.semiontd.tower.gamble;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kim.biryeong.semiontd.config.TowerBalanceConfig;
import kim.biryeong.semiontd.config.TowerBalanceRuntime;
import kim.biryeong.semiontd.game.GridPosition;
import kim.biryeong.semiontd.game.TeamId;
import kim.biryeong.semiontd.tower.ProductionTowerCatalogs;
import kim.biryeong.semiontd.web.WebCatalogExporter;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class GambleSlotsTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void resetBalance() {
        ProductionTowerCatalogs.reloadBuiltIns(TowerBalanceConfig.defaultConfig());
    }

    @Test
    void bundledGambleDefaultsMatchCodeForEveryTowerUpgradeAndAbility() {
        var bundled = TowerBalanceConfig.defaultConfig();
        var code = TowerBalanceConfig.codeDefaults();
        code.towers().forEach((id, stats) -> {
            if (id.startsWith("gamble_")) {
                assertEquals(stats, bundled.towers().get(id), id);
            }
        });
        code.upgradeCosts().forEach((id, cost) -> {
            if (id.startsWith("gamble_")) {
                assertEquals(cost, bundled.upgradeCosts().get(id), id);
            }
        });
        code.abilities().forEach((id, abilities) -> {
            if (id.startsWith("gamble_")) {
                assertEquals(abilities, bundled.abilities().get(id), id);
            }
        });
    }

    @Test
    void all216OutcomesHaveLowerExpectedEfficiencyAndPreserveJackpotCeilings() {
        int different = 0;
        int pairs = 0;
        int triples = 0;
        double total = 0.0;
        for (GambleSlots.Symbol a : GambleSlots.Symbol.values()) {
            for (GambleSlots.Symbol b : GambleSlots.Symbol.values()) {
                for (GambleSlots.Symbol c : GambleSlots.Symbol.values()) {
                    var result = GambleSlots.resolve(a, b, c);
                    assertTrue(result.score() > 0);
                    assertTrue(result.score() <= 300);
                    assertTrue(result.score() / result.statRewardCount() <= 150);
                    assertEquals(result.score(), GambleSlots.resolve(c, a, b).score());
                    total += result.score();
                    if (a == b && b == c) {
                        triples++;
                        assertEquals(2, result.statRewardCount());
                    } else if (a == b || a == c || b == c) {
                        pairs++;
                        assertEquals(1, result.statRewardCount());
                    } else {
                        different++;
                        assertEquals(25, result.score());
                    }
                }
            }
        }
        assertEquals(120, different);
        assertEquals(90, pairs);
        assertEquals(6, triples);
        assertEquals(55.5555555556, total / 216, 0.000001);
        assertEquals(0.9340659341, (total / 216 / 260) / (GambleRolls.expectedTwoDiceDelta() / 170), 0.000001);
        assertEquals(List.of("철 조각", "철", "구리", "금괴", "에메랄드", "다이아몬드"),
                java.util.Arrays.stream(GambleSlots.Symbol.values()).map(GambleSlots.Symbol::displayName).toList());
    }

    @Test
    void resolvedCatalogAndWebExportRetainDiceModelsAndSlotCosts() {
        var document = WebCatalogExporter.snapshot(0);
        for (var type : List.of(GambleTowers.DICE_T1, GambleTowers.DICE_T2, GambleTowers.DICE_T3)) {
            var resolved = TowerBalanceRuntime.resolve(type);
            var exported = document.towers().stream().filter(t -> t.id().equals(type.id())).findFirst().orElseThrow();
            int tier = Integer.parseInt(type.id().substring(type.id().length() - 1));
            assertEquals(GambleDiceVisuals.visual(tier, 1).blockbenchModelId(), resolved.blockbenchModelId());
            assertEquals(resolved.blockbenchModelId(), exported.visual().blockbenchModelId());
            assertEquals(resolved.visual().scale(), exported.visual().scale());
            assertFalse(exported.description().stream().anyMatch(line -> line.contains("{ability.")));
        }
        assertEquals(3, document.upgrades().stream().filter(u -> u.id().equals("spin_slots") && u.mineralCost() == 260).count());
        for (String id : GambleDiceVisuals.modelIds()) {
            assertNotNull(getClass().getResource("/model/" + id.replace(':', '/') + ".bbmodel"));
        }
        assertEquals(18, GambleDiceVisuals.modelIds().stream().distinct().count());
        for (var type : List.of(GambleTowers.SPECTATOR_T1, GambleTowers.SPECTATOR_T2, GambleTowers.SPECTATOR_T3)) {
            int tier = Integer.parseInt(type.id().substring(type.id().length() - 1));
            var resolved = TowerBalanceRuntime.resolve(type);
            String model = "semion-td:tower/gamble_slot_machine" + (tier == 1 ? "" : "_t" + tier);
            assertEquals(model, resolved.blockbenchModelId());
            assertEquals(tier == 1 ? 10 : tier == 2 ? 100 : 300, resolved.maxHealth());
            assertNotNull(getClass().getResource("/model/" + model.replace(':', '/') + ".bbmodel"));
            assertTrue(resolved.displayName().contains("슬롯머신"));
        }
        var poker = document.towers().stream().filter(t -> t.id().equals(GambleTowers.POKER_TABLE.id())).findFirst().orElseThrow();
        assertEquals("semion-td:prop/blackjack_table", poker.visual().blockbenchModelId());
        assertFalse(poker.description().stream().anyMatch(line -> line.contains("{ability.")));
    }

    @Test
    void pokerConfigBackfillsNewValuesAndRejectsInvalidDivisorsWithoutOverwritingCustomPrices() {
        var defaults = TowerBalanceConfig.defaultConfig();
        var partial = new TowerBalanceConfig(Map.of(), Map.of(
                TowerBalanceConfig.upgradeKey(GambleTowers.GAMBLER.id(), GambleBet.ODD.upgradeId()), 90L), Map.of());
        var merged = partial.withMissingDefaults(defaults);
        merged.validateForRuntime();
        assertEquals(200, merged.towers().get(GambleTowers.POKER_TABLE.id()).maxHealth());
        assertEquals(50, merged.towers().get(GambleTowers.POKER_TABLE.id()).mineralCost());
        assertEquals(50, merged.towers().get(GambleTowers.POKER_TABLE.id()).aggroPriority());
        assertEquals(170.0 / 3.0, merged.ability(GambleTowers.POKER_TABLE.id(), "healthScoreDivisor", -1));
        assertEquals(50000, merged.ability(GambleTowers.POKER_TABLE.id(), "specialScoreThreshold", -1));
        assertEquals(160, merged.ability(GambleTowers.POKER_TABLE.id(), "debuffDurationTicks", -1));
        assertEquals(90L, merged.upgradeCosts().get(TowerBalanceConfig.upgradeKey(GambleTowers.GAMBLER.id(), GambleBet.ODD.upgradeId())));
        var abilities = new LinkedHashMap<>(defaults.abilities());
        var poker = new LinkedHashMap<>(abilities.get(GambleTowers.POKER_TABLE.id()));
        poker.put("healthScoreDivisor", 0.0);
        abilities.put(GambleTowers.POKER_TABLE.id(), poker);
        var invalid = new TowerBalanceConfig(defaults.towers(), defaults.upgradeCosts(), abilities,
                defaults.illusionCloneQueue(), defaults.villagerAdv(), defaults.schemaVersion());
        assertThrows(IllegalArgumentException.class, invalid::validateForRuntime);
    }

    @Test
    void finalJackpotKeepsFullStatRewardEvenWhenOnlyOnePointRemains() {
        GambleState before = GambleState.EMPTY.recordAbility(GambleAbility.LOSS_INSURANCE, 499, "near cap");
        var result = GambleSlots.resolve(GambleSlots.Symbol.DIAMOND,
                GambleSlots.Symbol.DIAMOND, GambleSlots.Symbol.DIAMOND);
        GambleState after = before.recordStats(List.of(
                new GambleState.StatChange(GambleStat.DAMAGE, GambleBalance.statDelta(GambleStat.DAMAGE, 150), 5),
                new GambleState.StatChange(GambleStat.MAGIC_DAMAGE, GambleBalance.statDelta(GambleStat.MAGIC_DAMAGE, 150), 5)
        ), result.score(), result.display());
        assertEquals(500, after.cumulativeScore());
        assertEquals(75, after.damageDelta());
        assertEquals(75, after.magicDamageDelta());
        assertTrue(after.atScoreCap());
    }

    @Test
    void magicUsesTheSameConversionAndSurvivesUpgradeAndRebalance() {
        for (double score : new double[]{-140, -40, 25, 70, 150}) {
            assertEquals(GambleBalance.statDelta(GambleStat.DAMAGE, score),
                    GambleBalance.statDelta(GambleStat.MAGIC_DAMAGE, score));
        }
        GridPosition position = new GridPosition(0, 64, 0);
        UUID owner = UUID.randomUUID();
        GamblerTower original = new GamblerTower(GambleTowers.GAMBLER, owner, TeamId.RED, 1, position, position);
        assertEquals(5, original.type().damage());
        assertEquals(5, original.magicAttackDamage(null));
        original.setData(GamblerTower.STATE, GambleState.EMPTY.recordStat(GambleStat.MAGIC_DAMAGE, 35, 5, 70, "magic"));
        GamblerTower upgraded = new GamblerTower(GambleTowers.KING, owner, TeamId.RED, 1, position, position);
        upgraded.copyFrom(original, 0);
        assertEquals(35, upgraded.state().magicDamageDelta());
        assertEquals(55, upgraded.magicAttackDamage(null));
        assertEquals(35, upgraded.state().rebalanced(GambleTowers.KING).magicDamageDelta());
        assertEquals(1, GambleState.EMPTY.recordStat(GambleStat.MAGIC_DAMAGE, -100, 5, -200, "loss")
                .resolvedValue(GambleStat.MAGIC_DAMAGE, 5));
    }

    @Test
    void partialConfigBackfillsSlotsWithoutOverwritingExistingValuesAndRejectsOversizedRewards() {
        TowerBalanceConfig defaults = TowerBalanceConfig.defaultConfig();
        LinkedHashMap<String, Map<String, Double>> abilities = new LinkedHashMap<>(defaults.abilities());
        LinkedHashMap<String, Double> global = new LinkedHashMap<>(abilities.get(GambleBalance.GLOBAL_ID));
        global.keySet().removeIf(key -> key.startsWith("slot"));
        global.put("oddEvenWinScore", 80.0);
        abilities.put(GambleBalance.GLOBAL_ID, global);
        var partial = new TowerBalanceConfig(defaults.towers(), defaults.upgradeCosts(), abilities,
                defaults.illusionCloneQueue(), defaults.villagerAdv(), defaults.schemaVersion());
        var merged = partial.withMissingDefaults(defaults);
        merged.validateForRuntime();
        assertEquals(80.0, merged.abilities().get(GambleBalance.GLOBAL_ID).get("oddEvenWinScore"));
        assertEquals(300.0, merged.abilities().get(GambleBalance.GLOBAL_ID).get("slotTripleDiamond"));
        global.putAll(merged.abilities().get(GambleBalance.GLOBAL_ID));
        global.put("slotTripleDiamond", 301.0);
        var invalid = new TowerBalanceConfig(defaults.towers(), defaults.upgradeCosts(), abilities,
                defaults.illusionCloneQueue(), defaults.villagerAdv(), defaults.schemaVersion());
        assertThrows(IllegalArgumentException.class, invalid::validateForRuntime);
    }
}
