package kim.biryeong.semiontd.tower.pirate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kim.biryeong.semiontd.config.AttackKind;
import kim.biryeong.semiontd.config.EconomyConfig;
import kim.biryeong.semiontd.config.TowerBalanceConfig;
import kim.biryeong.semiontd.config.TowerBalanceRuntime;
import kim.biryeong.semiontd.config.WaveConfig;
import kim.biryeong.semiontd.entity.monster.KillSourceKind;
import kim.biryeong.semiontd.entity.monster.Monster;
import kim.biryeong.semiontd.game.EconomyService;
import kim.biryeong.semiontd.game.GridPosition;
import kim.biryeong.semiontd.game.PlayerEconomy;
import kim.biryeong.semiontd.game.PlayerLane;
import kim.biryeong.semiontd.game.RoundPhase;
import kim.biryeong.semiontd.game.SemionGame;
import kim.biryeong.semiontd.game.SemionPlayer;
import kim.biryeong.semiontd.game.TeamId;
import kim.biryeong.semiontd.game.TowerSellResult;
import kim.biryeong.semiontd.job.PirateTowerJob;
import kim.biryeong.semiontd.map.GameArena;
import kim.biryeong.semiontd.map.LaneRegionLayout;
import kim.biryeong.semiontd.tower.ProductionTowerCatalog;
import kim.biryeong.semiontd.tower.ProductionTowerCatalogs;
import kim.biryeong.semiontd.tower.ProductionTowerService;
import kim.biryeong.semiontd.tower.Tower;
import kim.biryeong.semiontd.tower.TowerType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import xyz.nucleoid.map_templates.BlockBounds;

class PirateEconomyTest {
    private final UUID owner = UUID.randomUUID();
    private SemionGame game;
    private SemionPlayer player;
    private PlayerLane lane;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setup() throws ReflectiveOperationException {
        ProductionTowerCatalogs.reloadBuiltIns(TowerBalanceConfig.defaultConfig());
        EconomyConfig economy = EconomyConfig.defaultConfig();
        game = new SemionGame(economy, WaveConfig.defaultConfig(), new GameArena(Map.of()));
        player = new SemionPlayer(owner, "pirate-economy-test", TeamId.RED, 1, new PlayerEconomy(economy));
        player.assignJob(new PirateTowerJob());
        player.economy().overrideStartingValues(100_000, 100_000, 10, 1);
        game.players().put(owner, player);
        game.teams().get(TeamId.RED).activate();
        LaneRegionLayout layout = new LaneRegionLayout(1, new Vec3(.5, 64, .5),
                List.of(new Vec3(.5, 64, 4.5)), new Vec3(.5, 64, 10.5),
                BlockBounds.of(new BlockPos(0, 63, 0), new BlockPos(40, 66, 10)),
                List.of(new GridPosition(0, 63, 10)));
        assertTrue(game.teams().get(TeamId.RED).addPlayer(player, null, layout));
        lane = game.playerLane(owner).orElseThrow();
        Field phase = SemionGame.class.getDeclaredField("phase");
        phase.setAccessible(true);
        phase.set(game, RoundPhase.PREPARE_AND_SUMMON);
        Field round = SemionGame.class.getDeclaredField("currentRound");
        round.setAccessible(true);
        round.setInt(game, 5);
        PirateStates.open(game, player);
    }

    @AfterEach
    void cleanup() {
        game.close();
        PirateStates.close(owner);
        ProductionTowerCatalogs.reloadBuiltIns(TowerBalanceConfig.defaultConfig());
    }

    @Test
    void twentyChestSalesKeepTwentyDiamondsAndOneHundredPermanentHealth() {
        buyFerrymen(0, 9, 0);
        long before = player.economy().diamond();
        for (int index = 0; index < 20; index++) {
            sell(buy(PirateTowers.SHABBY_CHEST, 30, 50));
        }
        assertEquals(20, player.economy().diamond() - before);
        assertEquals(100, lane.towers().stream().mapToDouble(Tower::permanentMaxHealthBonus).sum(), 1e-9);
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0, 0, -25",
            "1, 0, 0, -23",
            "12, 0, 0, -1",
            "11, 1, 0, 0",
            "13, 0, 0, 0",
            "0, 9, 0, 1",
            "0, 0, 7, 1",
            "0, 0, 22, 31"
    })
    void directSalesOnlyHalvePositiveProfit(int ordinary, int veteran, int legendary, long expectedProfit) {
        buyFerrymen(ordinary, veteran, legendary);
        long before = player.economy().diamond();
        sell(buy(PirateTowers.SHABBY_CHEST, 30, 50));
        assertEquals(expectedProfit, player.economy().diamond() - before);
    }

    @Test
    void upgradedChestUsesActualCumulativePurchaseCost() {
        buyFerrymen(0, 0, 22);
        long before = player.economy().diamond();
        PirateTower original = buy(PirateTowers.SHABBY_CHEST, 30, 50);
        PirateTower upgraded = new PirateTower(PirateTowers.DEEP_CHEST, owner, TeamId.RED, 1, original.position());
        assertTrue(player.economy().spendDiamond(100));
        upgraded.copyFrom(original, 100);
        assertTrue(lane.removeTower(original));
        lane.addTower(upgraded);
        PirateStates.recordDiamondSpend(player, 100);
        assertEquals(150, upgraded.paidMineralCost());
        assertEquals(75, upgraded.sellRefundAmount());
        sell(upgraded);
        assertEquals(6, player.economy().diamond() - before);
        assertEquals(10, lane.towers().stream().mapToDouble(Tower::permanentMaxHealthBonus).sum(), 1e-9);
    }

    @Test
    void regularIncomeStillPaysEveryOwnedFerrymanInFull() {
        buyFerrymen(1, 1, 1);
        long before = player.economy().diamond();
        PirateStates.grantFerrymanIncome(player);
        assertEquals(9, player.economy().diamond() - before);
        before = player.economy().diamond();
        new EconomyService(EconomyConfig.defaultConfig(), game).payRoundIncome(game.players().values(), game.teams());
        assertEquals(19, player.economy().diamond() - before);
        Monster monster = new Monster("pirate-income-test", TeamId.RED, 1, Optional.empty(), Optional.empty(),
                10, 0, 1, AttackKind.MELEE, "minecraft:zombie", 5);
        monster.recordLastHit(owner, KillSourceKind.TOWER);
        before = player.economy().diamond();
        new EconomyService(EconomyConfig.defaultConfig(), game).awardMonsterKillReward(monster, game.players());
        assertEquals(14, player.economy().diamond() - before);
    }

    @Test
    void maturedChestKeepsItsRewardRefundAndFullFerrymanBonus() {
        buyFerrymen(0, 9, 0);
        PirateTower chest = buy(PirateTowers.SHABBY_CHEST, 30, 50);
        long before = player.economy().diamond();
        // The existing maturity schedule is outside the direct-sale change.
        chest.onRoundEnded(lane, chest.placedRound() + 5);
        assertFalse(lane.towers().contains(chest));
        assertEquals(142, player.economy().diamond() - before);
        assertEquals(5, lane.towers().stream().mapToDouble(Tower::permanentMaxHealthBonus).sum(), 1e-9);
    }

    @Test
    void admiralSpendingKeepsItsPaybackAndFullFerrymanBonus() {
        buyFerrymen(0, 9, 0);
        buy(PirateTowers.ADMIRAL, 30, 1_000);
        TowerBalanceConfig defaults = TowerBalanceConfig.defaultConfig();
        Map<String, Map<String, Double>> abilities = new LinkedHashMap<>(defaults.abilities());
        Map<String, Double> admiral = new LinkedHashMap<>(abilities.get(PirateTowers.ADMIRAL.id()));
        // Exercise every existing effect once without relying on the random selection order.
        admiral.put("effectCount", 3.0);
        admiral.put("paybackLow", 50.0);
        admiral.put("paybackHigh", 50.0);
        abilities.put(PirateTowers.ADMIRAL.id(), admiral);
        TowerBalanceRuntime.apply(new TowerBalanceConfig(defaults.towers(), defaults.upgradeCosts(), abilities));
        long before = player.economy().diamond();
        long progressBefore = PirateStates.admiralProgress(owner);
        assertTrue(player.economy().spendDiamond(200));
        PirateStates.recordDiamondSpend(player, 200);
        assertEquals(-123, player.economy().diamond() - before);
        assertEquals(progressBefore, PirateStates.admiralProgress(owner));
    }

    private void buyFerrymen(int ordinary, int veteran, int legendary) {
        int position = 0;
        for (int index = 0; index < ordinary; index++) buy(PirateTowers.FERRYMAN, position++, 200);
        for (int index = 0; index < veteran; index++) buy(PirateTowers.VETERAN_FERRYMAN, position++, 700);
        for (int index = 0; index < legendary; index++) buy(PirateTowers.LEGENDARY_FERRYMAN, position++, 1_500);
        assertTrue(position + 1 <= 23, "Sale scenarios stay within the maximum legal roster size");
    }

    private PirateTower buy(TowerType type, int x, long paid) {
        // Model paid placement without a Minecraft world; sales use the production service.
        assertTrue(player.economy().spendDiamond(paid));
        PirateTower tower = (PirateTower) ProductionTowerCatalog.find(type.id()).orElseThrow()
                .create(owner, TeamId.RED, 1, new GridPosition(x, 63, 1));
        tower.recordPlacementEconomy(paid, game.currentRound());
        lane.addTower(tower);
        PirateStates.recordDiamondSpend(player, paid);
        return tower;
    }

    private void sell(PirateTower tower) {
        var result = ProductionTowerService.sellTower(game, owner, tower.position());
        assertEquals(TowerSellResult.SUCCESS, result.result());
        assertEquals(tower.sellRefundAmount(), result.refundAmount());
    }
}
