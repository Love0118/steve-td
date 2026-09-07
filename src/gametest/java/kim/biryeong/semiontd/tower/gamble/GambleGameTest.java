package kim.biryeong.semiontd.tower.gamble;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kim.biryeong.semiontd.config.AttackKind;
import kim.biryeong.semiontd.config.EconomyConfig;
import kim.biryeong.semiontd.config.TowerBalanceConfig;
import kim.biryeong.semiontd.config.TowerBalanceRuntime;
import kim.biryeong.semiontd.config.WaveConfig;
import kim.biryeong.semiontd.effect.TimedEffectType;
import kim.biryeong.semiontd.entity.SemionEntityTypes;
import kim.biryeong.semiontd.entity.boss.BossMonster;
import kim.biryeong.semiontd.entity.monster.Monster;
import kim.biryeong.semiontd.entity.monster.SemionMonsterEntity;
import kim.biryeong.semiontd.entity.tower.SemionTowerEntity;
import kim.biryeong.semiontd.game.GridPosition;
import kim.biryeong.semiontd.game.AssignedParticipant;
import kim.biryeong.semiontd.game.MatchMode;
import kim.biryeong.semiontd.game.ParticipantSelectionPlan;
import kim.biryeong.semiontd.game.PlayerLane;
import kim.biryeong.semiontd.game.SemionGame;
import kim.biryeong.semiontd.game.TeamId;
import kim.biryeong.semiontd.game.TeamLaneGroup;
import kim.biryeong.semiontd.game.TowerUpgradeResult;
import kim.biryeong.semiontd.gametest.SyntheticArenaFactory;
import kim.biryeong.semiontd.job.JobContext;
import kim.biryeong.semiontd.map.LaneRegionLayout;
import kim.biryeong.semiontd.tower.ProductionTowerCatalog;
import kim.biryeong.semiontd.tower.ProductionTowerCatalogs;
import kim.biryeong.semiontd.tower.ProductionTowerService;
import kim.biryeong.semiontd.tower.Tower;
import kim.biryeong.semiontd.tower.TowerUpgradeOption;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import xyz.nucleoid.map_templates.BlockBounds;

public final class GambleGameTest {
    @GameTest(maxTicks = 80)
    public void pokerStoresItsRandomDebuffsAndOnlyAppliesThoseShownInDetails(GameTestHelper context) {
        ProductionTowerCatalogs.reloadBuiltIns(TowerBalanceConfig.defaultConfig());
        UUID owner = stableUuid("poker-random-debuffs");
        PlayerLane lane = testLane(context, owner);
        TeamLaneGroup group = new TeamLaneGroup(TeamId.RED, BossMonster.defaultBoss(TeamId.RED));
        group.addLane(lane);
        prepareFloor(context);
        SemionMonsterEntity target = null;
        try {
            PokerTableTower table = poker(owner, floor(context, 5, 2, 5));
            lane.addTower(table);
            int[] draws = {0, 2}; // One debuff, choosing armor rather than the old fixed attack-damage debuff.
            int[] index = {0};
            table.resolveHand(lane, 1000, GamblePoker.evaluate(0, 3, 7), bound -> draws[index[0]++]);
            require(table.deathDebuffs().equals(List.of(GamblePoker.DeathDebuff.ARMOR)), "The chosen subset must be saved.");
            table.resetForRound(lane);
            table.refreshType(TowerBalanceRuntime.resolve(GambleTowers.POKER_TABLE), lane);
            PokerTableTower copied = poker(owner, table.position());
            copied.copyFrom(table, 0);
            lane.replaceTower(table, copied);
            require(copied.deathDebuffs().equals(table.deathDebuffs()), "Reset, reload and copying cannot reroll debuffs.");
            String details = copied.runtimeDetailLines().stream().filter(line -> line.startsWith("사망 디버프")).findFirst().orElseThrow();
            require(details.contains("방어력") && !details.contains("공격"), "Details must list only the selected debuffs.");
            SemionTowerEntity source = entity(lane, copied);
            target = spawnTarget(context, lane, source.position().add(1, 0, 0), "random-debuff-target", 2000);
            lane.killTower(copied);
            require(close(target.activeTimedEffectMagnitude(TimedEffectType.MONSTER_ARMOR_REDUCTION), 0.2), "Selected armor debuff applies.");
            require(close(target.activeTimedEffectMagnitude(TimedEffectType.MONSTER_ATTACK_DAMAGE_REDUCTION), 0)
                            && close(target.activeTimedEffectMagnitude(TimedEffectType.MONSTER_ATTACK_SPEED_REDUCTION), 0),
                    "Unselected debuffs must not apply.");
            context.succeed();
        } finally {
            if (target != null) target.discard();
            group.closeRuntime();
        }
    }

    @GameTest(maxTicks = 80)
    public void gamblersAndSlotsFaceTheWaveSpawnAfterPlacementAndIdleTicks(GameTestHelper context) {
        ProductionTowerCatalogs.reloadBuiltIns(TowerBalanceConfig.defaultConfig());
        UUID owner = stableUuid("gamble-wave-facing");
        PlayerLane lane = testLane(context, owner);
        prepareFloor(context);
        try {
            for (var type : List.of(GambleTowers.GAMBLER, GambleTowers.KING, GambleTowers.DARK_KING,
                    GambleTowers.SPECTATOR_T1, GambleTowers.SPECTATOR_T2, GambleTowers.SPECTATOR_T3)) {
                GridPosition position = floor(context, 5, 2, 5);
                var resolved = TowerBalanceRuntime.resolve(type);
                kim.biryeong.semiontd.tower.EntityBackedTower tower = GambleTowers.isSpectator(type)
                        ? support(type, owner, position)
                        : new GamblerTower(resolved, owner, TeamId.RED, 1, position, position);
                lane.addTower(tower);
                var source = entity(lane, tower);
                var direction = lane.laneLayout().spawn().subtract(source.position()).multiply(1, 0, 1).normalize();
                for (int step = 0; step < 2; step++) {
                    double radians = Math.toRadians(source.yBodyRot);
                    require(new Vec3(-Math.sin(radians), 0, Math.cos(radians)).dot(direction) > 0.999,
                            "Every gambler and slot tier must face its actual wave spawn.");
                    if (GambleTowers.isSpectator(type)) {
                        var holder = (de.tomalbrc.bil.core.holder.entity.living.LivingEntityHolder<?>) source.getHolder();
                        require(holder.getAnimator().isPlaying("idle"), "Static slots need idle poses to update display yaw.");
                        var animator = (de.tomalbrc.bil.core.component.AnimationComponent) holder.getAnimator();
                        animator.tickAnimations();
                        for (var bone : holder.getBones()) {
                            var pose = animator.findPose(null, bone);
                            require(pose != null, "Every visible slot bone must participate in the idle pose update.");
                            holder.updateElement(null, bone, pose.pose());
                            require(close(bone.element().getYaw(), source.yBodyRot),
                                    "The displayed slot bone must receive the wave-facing yaw, not just its parent entity.");
                        }
                    }
                    source.setYRot(47);
                    source.setYHeadRot(47);
                    source.yBodyRot = 47;
                    tower.tick(lane);
                }
                lane.removeTower(tower);
            }
            context.succeed();
        } finally {
            lane.clearTowers();
        }
    }

    @GameTest(maxTicks = 80)
    public void gambleRevealOwnsTheActionbarUntilItsResultExpires(GameTestHelper context) {
        var player = context.makeMockServerPlayerInLevel();
        var reveal = new GambleReveal(GambleReveal.Kind.DICE, List.of(6, 6), "주사위", "더블", "더블", true);
        try {
            kim.biryeong.semiontd.ui.GambleRevealService.start(player, reveal);
            require(kim.biryeong.semiontd.ui.GambleRevealService.isRolling(player.getUUID()), "A new reveal starts rolling.");
            var expected = kim.biryeong.semiontd.ui.GambleRevealService.actionbar(player.getUUID());
            require(expected.equals(kim.biryeong.semiontd.ui.SemionHudTextService.actionbarTextFor(player.getUUID(), null)),
                    "The sidebar HUD must show the reveal instead of overwriting it.");
            require(expected.equals(kim.biryeong.semiontd.ui.SemionDisplayHudService.actionbarTextFor(player.getUUID(), null)),
                    "The display HUD must also preserve the reveal.");
            for (int i = 0; i < reveal.durationTicks(); i++) {
                kim.biryeong.semiontd.ui.GambleRevealService.tick(context.getLevel().getServer());
                if (i == 0) require(kim.biryeong.semiontd.ui.GambleRevealService.isRolling(player.getUUID()),
                        "An online player's animation must remain active until its result is revealed.");
                if (i == reveal.revealTick() - 1) {
                    require(!kim.biryeong.semiontd.ui.GambleRevealService.isRolling(player.getUUID())
                                    && kim.biryeong.semiontd.ui.GambleRevealService.actionbar(player.getUUID()).isPresent(),
                            "Revealed results stop rolling while retaining the final display.");
                }
            }
            require(!kim.biryeong.semiontd.ui.GambleRevealService.isRolling(player.getUUID())
                            && kim.biryeong.semiontd.ui.GambleRevealService.actionbar(player.getUUID()).isEmpty(),
                    "Finished reveals must release the actionbar.");
            context.succeed();
        } finally {
            kim.biryeong.semiontd.ui.GambleRevealService.clear(player.getUUID());
            player.discard();
        }
    }

    @GameTest(maxTicks = 80)
    public void repeatedBetsReplaceTheRevealWithoutBlockingUpgrades(GameTestHelper context) {
        ProductionTowerCatalogs.reloadBuiltIns(TowerBalanceConfig.defaultConfig());
        var player = context.makeMockServerPlayerInLevel();
        UUID owner = player.getUUID();
        SemionGame game = startedGambleGame(context, owner, "rapid-bets");
        try {
            PlayerLane lane = game.playerLane(owner).orElseThrow();
            GridPosition position = emptyPosition(lane);
            lane.addTower(gambler(owner, position));
            var economy = game.players().get(owner).economy();
            economy.addMineral(2000);
            long before = economy.diamond();
            for (int i = 0; i < 3; i++) {
                require(ProductionTowerService.upgradeTower(game, owner, position, GambleBet.ODD.upgradeId())
                        == TowerUpgradeResult.SUCCESS, "Successive bets must succeed without advancing the reveal clock.");
                require(kim.biryeong.semiontd.ui.GambleRevealService.isRolling(owner), "Each bet must start a reveal.");
            }
            GamblerTower upgraded = (GamblerTower) lane.towerAt(position);
            require(upgraded.state().totalBets() == 3 && economy.diamond() == before - 3 * 85,
                    "Each accepted bet must be recorded and charged exactly once.");
            require(ProductionTowerService.upgradeTower(game, owner, position, GambleBet.SLOTS.upgradeId())
                    == TowerUpgradeResult.UPGRADE_REQUIREMENTS_NOT_MET, "Animation skipping must not bypass support requirements.");
            PokerTableTower table = poker(owner, emptyPosition(lane));
            lane.addTower(table);
            require(ProductionTowerService.betPoker(game, owner, table.originalPosition(), table.betToken(), 200)
                    == TowerUpgradeResult.SUCCESS, "A gambler reveal must not block another poker table.");
            var replacement = new GambleReveal(GambleReveal.Kind.SLOTS, List.of(1, 2, 3), "new slots", "done", "result", true);
            for (var result : List.of(replacement,
                    new GambleReveal(GambleReveal.Kind.CARDS, List.of(0, 14, 28), "cards title", "caption", "card result", true),
                    new GambleReveal(GambleReveal.Kind.DICE, List.of(2, 6), "dice title", "caption", "dice result", true))) {
                var message = kim.biryeong.semiontd.ui.GambleRevealService.resultMessage(result);
                String plain = message.getString();
                require(plain.startsWith("\n\n") && plain.endsWith("\n") && plain.chars().filter(c -> c == '\n').count() == 3,
                        "Chat glyphs must occupy the third line, with a blank line below.");
                require(plain.contains(result.result()) && !plain.contains("…"), "Chat must contain the full settled result.");
                require(!plain.contains(result.label()), "Chat must omit the repeated bet title.");
                for (int outcome : result.outcomes()) {
                    var glyph = switch (result.kind()) {
                        case CARDS -> kim.biryeong.semiontd.ui.rp.GambleGlyphs.card(outcome);
                        case DICE -> kim.biryeong.semiontd.ui.rp.GambleGlyphs.die(outcome);
                        case SLOTS -> kim.biryeong.semiontd.ui.rp.GambleGlyphs.slot(outcome);
                    };
                    require(message.getSiblings().get(0).getSiblings().contains(glyph),
                            "Chat must preserve the settled glyph and its resource-pack font style.");
                }
            }
            kim.biryeong.semiontd.ui.GambleRevealService.start(player, replacement);
            require(kim.biryeong.semiontd.ui.GambleRevealService.actionbar(owner).orElseThrow()
                    .equals(kim.biryeong.semiontd.ui.GambleRevealService.render(replacement, replacement.frameAt(0))),
                    "The latest bet must replace the actionbar immediately rather than queue behind earlier bets.");
            context.succeed();
        } finally {
            kim.biryeong.semiontd.ui.GambleRevealService.clear(owner);
            game.close();
            player.discard();
        }
    }

    @GameTest(maxTicks = 80)
    public void pokerHealthTargetsAreForOneThousandDiamondsAndScaleWithSmallerBets(GameTestHelper context) {
        ProductionTowerCatalogs.reloadBuiltIns(TowerBalanceConfig.defaultConfig());
        UUID owner = stableUuid("poker-thousand-diamond-budget");
        PlayerLane lane = testLane(context, owner);
        TeamLaneGroup group = new TeamLaneGroup(TeamId.RED, BossMonster.defaultBoss(TeamId.RED));
        group.addLane(lane);
        prepareFloor(context);
        int[][] cards = {{10, 13, 28}, {11, 13, 28}, {12, 13, 28}, {0, 13, 28}, {12, 25, 28},
                {0, 3, 7}, {0, 14, 28}, {0, 13, 26}, {0, 1, 2}};
        double[] health = {656.470588, 727.058824, 797.647059, 832.941176, 1044.705882,
                1062.352941, 1150.588235, 1238.823529, 1238.823529};
        try {
            for (int index = 0; index < cards.length; index++) {
                for (int bet : List.of(200, 1000)) {
                    PokerTableTower table = poker(owner, floor(context, 4, 2, 3));
                    lane.addTower(table);
                    table.resolveHand(lane, bet, GamblePoker.evaluate(cards[index][0], cards[index][1], cards[index][2]));
                    double expectedHealth = 180 + (health[index] - 180) * bet / 1000.0;
                    require(Math.abs(table.currentMaxHealth() - expectedHealth) < 0.001,
                            "Poker health must match the thousand-diamond budget for hand " + index + " at bet " + bet);
                    require(bet == 1000 && index >= 5 ? table.debuffCount() >= 1 && table.debuffCount() <= 3
                                    : table.debuffCount() == 0,
                            "Small bets must not unlock the high-hand death debuffs.");
                    lane.removeTower(table);
                }
            }
            context.succeed();
        } finally {
            group.closeRuntime();
        }
    }

    @GameTest(maxTicks = 80)
    public void displayedGamblerDamageComponentsMatchBuffedCombatDamage(GameTestHelper context) {
        ProductionTowerCatalogs.reloadBuiltIns(TowerBalanceConfig.defaultConfig());
        UUID owner = stableUuid("gamble-damage-stats");
        PlayerLane lane = testLane(context, owner);
        TeamLaneGroup group = new TeamLaneGroup(TeamId.RED, BossMonster.defaultBoss(TeamId.RED));
        group.addLane(lane);
        prepareFloor(context);
        SemionMonsterEntity target = null;
        try {
            GridPosition position = floor(context, 4, 2, 3);
            GamblerTower king = new GamblerTower(TowerBalanceRuntime.resolve(GambleTowers.KING), owner,
                    TeamId.RED, 1, position, position);
            lane.addTower(king);
            king.markWaveStarted(1);
            SemionTowerEntity source = entity(lane, king);
            source.setPersistentEffect(TimedEffectType.TOWER_DAMAGE_BONUS, supportTestSource("display-percent"), 0.2);
            source.setPersistentEffect(TimedEffectType.TOWER_FLAT_DAMAGE_BONUS, supportTestSource("display-flat"), 6);
            source.setPersistentEffect(TimedEffectType.TOWER_FINAL_DAMAGE_BONUS, supportTestSource("display-final"), 0.5);
            GamblerTower.AttackDamage displayed = king.currentAttackDamage(source);
            require(close(displayed.physical(), 45) && close(displayed.magic(), 36),
                    "The two stat lines must apply flat, percentage and final bonuses to their own components.");
            target = spawnTarget(context, lane, source.position().add(0, 0, 2), "displayed-damage-target");
            source.damageTargetResult(target, source.attackDamageAmount(target));
            require(close(king.roundPhysicalDamageDealt(), displayed.physical())
                            && close(king.roundMagicDamageDealt(), displayed.magic()),
                    "Displayed components must equal their actual typed combat damage.");
            context.succeed();
        } finally {
            if (target != null) target.discard();
            group.closeRuntime();
        }
    }

    @GameTest(maxTicks = 80)
    public void bothGambleKingsSplitTheirBaseAttackEvenlyBetweenPhysicalAndMagic(GameTestHelper context) {
        ProductionTowerCatalogs.reloadBuiltIns(TowerBalanceConfig.defaultConfig());
        UUID owner = stableUuid("gamble-king-mixed-damage");
        PlayerLane lane = testLane(context, owner);
        TeamLaneGroup group = new TeamLaneGroup(TeamId.RED, BossMonster.defaultBoss(TeamId.RED));
        group.addLane(lane);
        prepareFloor(context);
        SemionMonsterEntity target = null;
        try {
            for (var type : List.of(GambleTowers.KING, GambleTowers.DARK_KING)) {
                GridPosition position = floor(context, 4, 2, 3);
                GamblerTower king = new GamblerTower(TowerBalanceRuntime.resolve(type), owner,
                        TeamId.RED, 1, position, position);
                lane.addTower(king);
                king.markWaveStarted(1);
                double halfDamage = type == GambleTowers.KING ? 20.0 : 22.0;
                SemionTowerEntity source = entity(lane, king);
                target = spawnTarget(context, lane, source.position().add(0, 0, 2), "king-mixed-target");
                require(close(king.type().damage(), halfDamage) && close(king.magicAttackDamage(source), halfDamage),
                        "Each king's base attack must be split equally, without doubling the original total.");
                source.damageTargetResult(target, source.attackDamageAmount(target));
                require(close(king.roundPhysicalDamageDealt(), halfDamage)
                                && close(king.roundMagicDamageDealt(), halfDamage),
                        "King basic attacks must deal and record equal physical and magic components.");
                target.discard();
                target = null;
                lane.removeTower(king);
            }
            context.succeed();
        } finally {
            if (target != null) target.discard();
            group.closeRuntime();
        }
    }

    @GameTest(maxTicks = 80)
    public void everySlotJackpotSplitsItsScoreBetweenTwoDistinctStats(GameTestHelper context) {
        ProductionTowerCatalogs.reloadBuiltIns(TowerBalanceConfig.defaultConfig());
        UUID owner = stableUuid("gamble-slot-jackpot-split");
        PlayerLane lane = testLane(context, owner);
        TeamLaneGroup group = new TeamLaneGroup(TeamId.RED, BossMonster.defaultBoss(TeamId.RED));
        group.addLane(lane);
        prepareFloor(context);
        try {
            for (GambleSlots.Symbol symbol : GambleSlots.Symbol.values()) {
                GamblerTower gambler = gambler(owner, floor(context, 4, 2, 3));
                gambler.setData(GamblerTower.STATE,
                        GambleState.EMPTY.recordAbility(GambleAbility.LOSS_INSURANCE, 0, "insured"));
                lane.addTower(gambler);
                long seed = 0;
                while (true) {
                    var random = net.minecraft.util.RandomSource.create(seed);
                    if (random.nextInt(6) == symbol.ordinal() && random.nextInt(6) == symbol.ordinal()
                            && random.nextInt(6) == symbol.ordinal()) break;
                    seed++;
                }
                entity(lane, gambler).getRandom().setSeed(seed);
                gambler.onUpgradeApplied(lane, ProductionTowerCatalog.upgrade(
                        gambler.type(), GambleBet.SLOTS.upgradeId()).orElseThrow());
                double score = symbol.defaultTripleScore();
                GambleState state = gambler.state();
                double[] awardedPoints = {state.maxHealthDelta() / GambleBalance.MAX_HEALTH_PER_SCORE,
                        state.damageDelta() / GambleBalance.DAMAGE_PER_SCORE,
                        state.magicDamageDelta() / GambleBalance.DAMAGE_PER_SCORE,
                        state.rangeDelta() / GambleBalance.RANGE_PER_SCORE};
                int rewardedStats = 0;
                for (double points : awardedPoints) {
                    if (points > 0) {
                        rewardedStats++;
                        require(close(points, score / 2), "Each jackpot stat must receive half of the total score.");
                    }
                }
                require(rewardedStats == 2 && close(state.cumulativeScore(), score),
                        "A jackpot must split across exactly two distinct stats and record the total score once.");
                lane.removeTower(gambler);
            }
            context.succeed();
        } finally {
            group.closeRuntime();
        }
    }

    @GameTest(maxTicks = 80)
    public void pokerExplosionUsesFivePercentBeforeBetAndTenPercentAfterAnySurvivingBet(GameTestHelper context) {
        ProductionTowerCatalogs.reloadBuiltIns(TowerBalanceConfig.defaultConfig());
        UUID owner = stableUuid("poker-death-upgrade-rate");
        PlayerLane lane = testLane(context, owner);
        TeamLaneGroup group = new TeamLaneGroup(TeamId.RED, BossMonster.defaultBoss(TeamId.RED));
        group.addLane(lane);
        prepareFloor(context);
        SemionMonsterEntity target = null;
        try {
            PokerTableTower base = poker(owner, floor(context, 4, 2, 3));
            lane.addTower(base);
            target = spawnTarget(context, lane, entity(lane, base).position().add(1, 0, 0), "poker-base-target");
            require(close(base.deathDamageRatio(), 0.05), "An unupgraded table must use five percent.");
            lane.killTower(base);
            require(close(target.runtimeMonster().health(), 91), "A base 180-health table must explode for nine damage.");
            target.discard();
            target = null;
            lane.removeTower(base);

            PokerTableTower upgraded = poker(owner, floor(context, 4, 2, 3));
            lane.addTower(upgraded);
            upgraded.resolveHand(lane, 200, GamblePoker.evaluate(0, 16, 35));
            require(upgraded.debuffCount() == 0 && close(upgraded.deathDamageRatio(), 0.1),
                    "Even a weak surviving hand must upgrade the explosion without requiring a special ability.");
            upgraded.resetForRound(lane);
            upgraded.refreshType(TowerBalanceRuntime.resolve(GambleTowers.POKER_TABLE), lane);
            target = spawnTarget(context, lane, entity(lane, upgraded).position().add(1, 0, 0), "poker-upgraded-target");
            double expectedDamage = upgraded.currentMaxHealth() * 0.1;
            lane.killTower(upgraded);
            require(Math.abs(target.runtimeMonster().health() - (100 - expectedDamage)) < 0.001,
                    "The upgraded explosion must retain ten percent after round reset and balance reload.");
            context.succeed();
        } finally {
            if (target != null) target.discard();
            group.closeRuntime();
        }
    }

    @GameTest(maxTicks = 80)
    public void pokerLossIsPermanentAndSmallHighHandsOnlyGainHealth(GameTestHelper context) {
        ProductionTowerCatalogs.reloadBuiltIns(TowerBalanceConfig.defaultConfig());
        UUID owner = stableUuid("poker-results");
        PlayerLane lane = testLane(context, owner);
        TeamLaneGroup group = new TeamLaneGroup(TeamId.RED, BossMonster.defaultBoss(TeamId.RED));
        group.addLane(lane);
        prepareFloor(context);
        try {
            PokerTableTower lost = poker(owner, floor(context, 3, 2, 3));
            lane.addTower(lost);
            lost.resolveHand(lane, 1000, GamblePoker.evaluate(0, 16, 31));
            require(!lane.towers().contains(lost), "Low high-card loss must permanently remove the logical tower.");
            PokerTableTower weak = poker(owner, floor(context, 4, 2, 3));
            lane.addTower(weak);
            weak.resolveHand(lane, 200, GamblePoker.evaluate(0, 16, 35));
            require(close(weak.currentMaxHealth(), 180 + 1200.0 * 3 / 170) && weak.debuffCount() == 0,
                    "J high must keep base health and grant only the weak six-point reward.");
            PokerTableTower flush = poker(owner, floor(context, 5, 2, 3));
            lane.addTower(flush);
            flush.resolveHand(lane, 200, GamblePoker.evaluate(0, 3, 7));
            require(close(flush.currentMaxHealth(), 180 + 10000.0 * 3 / 170) && flush.debuffCount() == 0,
                    "A small flush bet must grant health without a special ability.");
            for (var type : List.of(GambleTowers.SPECTATOR_T1, GambleTowers.SPECTATOR_T2, GambleTowers.SPECTATOR_T3)) {
                GambleSupportTower slots = support(type, owner, floor(context, 7, 2, 3));
                lane.addTower(slots);
                SemionTowerEntity source = entity(lane, slots);
                require(source.hasBilModelHolder() && source.blockbenchModelId().equals(type.blockbenchModelId()),
                        "Each slot-machine tier must load its own BIL model.");
                require(close(slots.currentMaxHealth(), type.maxHealth()), "Slot-machine tier health must match its type.");
                lane.removeTower(slots);
            }
            context.succeed();
        } finally {
            group.closeRuntime();
        }
    }

    @GameTest(maxTicks = 80)
    public void supportBuildingsGateMatchingBetsAndSellingLocksThemAgain(GameTestHelper context) {
        ProductionTowerCatalogs.reloadBuiltIns(TowerBalanceConfig.defaultConfig());
        UUID owner = stableUuid("gamble-unlocks");
        SemionGame game = startedGambleGame(context, owner, "gamble-unlocks");
        try {
            PlayerLane lane = game.playerLane(owner).orElseThrow();
            GridPosition position = emptyPosition(lane);
            GamblerTower gambler = gambler(owner, position);
            lane.addTower(gambler);
            game.players().get(owner).economy().addMineral(2000);
            long money = game.players().get(owner).economy().diamond();
            for (GambleBet bet : List.of(GambleBet.TWO_DICE, GambleBet.SLOTS)) {
                require(ProductionTowerService.upgradeTower(game, owner, position, bet.upgradeId())
                        == TowerUpgradeResult.UPGRADE_REQUIREMENTS_NOT_MET, "Missing support must reject the bet.");
            }
            require(game.players().get(owner).economy().diamond() == money, "Locked bets must not charge.");
            require(ProductionTowerService.upgradeTower(game, owner, position, GambleBet.ODD.upgradeId())
                    == TowerUpgradeResult.SUCCESS, "Odd/even must remain available without support.");
            require(game.players().get(owner).economy().diamond() == money - 85, "Odd/even must charge 85.");
            GambleSupportTower other = support(GambleTowers.DICE_T3, stableUuid("other-owner"), emptyPosition(lane));
            lane.addTower(other);
            require(ProductionTowerService.upgradeTower(game, owner, position, GambleBet.TWO_DICE.upgradeId())
                    == TowerUpgradeResult.UPGRADE_REQUIREMENTS_NOT_MET, "Another player's support cannot unlock bets.");
            lane.removeTower(other);
            GambleSupportTower dice = support(GambleTowers.DICE_T2, owner, emptyPosition(lane));
            lane.addTower(dice);
            require(ProductionTowerService.upgradeTower(game, owner, position, GambleBet.SLOTS.upgradeId())
                    == TowerUpgradeResult.UPGRADE_REQUIREMENTS_NOT_MET, "Dice cannot unlock slots.");
            require(ProductionTowerService.upgradeTower(game, owner, position, GambleBet.TWO_DICE.upgradeId())
                    == TowerUpgradeResult.SUCCESS, "An owned dice tower must unlock two dice.");
            require(game.players().get(owner).economy().diamond() == money - 85 - 170, "Two dice must charge 170.");
            lane.killTower(dice);
            require(ProductionTowerService.upgradeTower(game, owner, position, GambleBet.TWO_DICE.upgradeId())
                    == TowerUpgradeResult.UPGRADE_REQUIREMENTS_NOT_MET, "A dead support cannot unlock bets.");
            dice.resetForRound(lane);
            lane.removeTower(dice);
            require(ProductionTowerService.upgradeTower(game, owner, position, GambleBet.TWO_DICE.upgradeId())
                    == TowerUpgradeResult.UPGRADE_REQUIREMENTS_NOT_MET, "Removing support must lock its bet.");
            context.succeed();
        } finally {
            game.close();
        }
    }

    @GameTest(maxTicks = 80)
    public void pokerSliderTransactionValidatesTokenBoundsFundsAndOneBet(GameTestHelper context) {
        ProductionTowerCatalogs.reloadBuiltIns(TowerBalanceConfig.defaultConfig());
        UUID owner = stableUuid("poker-transaction");
        SemionGame game = startedGambleGame(context, owner, "poker-transaction");
        try {
            PlayerLane lane = game.playerLane(owner).orElseThrow();
            GridPosition position = emptyPosition(lane);
            PokerTableTower table = poker(owner, position);
            lane.addTower(table);
            var economy = game.players().get(owner).economy();
            economy.spendMineral(economy.diamond());
            require(ProductionTowerService.betPoker(game, owner, position, table.betToken(), 200)
                    == TowerUpgradeResult.NOT_ENOUGH_MINERAL, "Insufficient funds must reject the bet.");
            economy.addMineral(2000);
            for (long invalid : new long[]{199, 1001, Long.MAX_VALUE}) {
                require(ProductionTowerService.betPoker(game, owner, position, table.betToken(), invalid)
                        == TowerUpgradeResult.UPGRADE_REQUIREMENTS_NOT_MET, "Server must validate bet bounds.");
            }
            require(ProductionTowerService.betPoker(game, owner, position, UUID.randomUUID(), 500)
                    == TowerUpgradeResult.UPGRADE_REQUIREMENTS_NOT_MET, "Stale dialogs cannot bet on another instance.");
            require(ProductionTowerService.upgradeTower(game, owner, position, GamblePoker.UPGRADE_ID)
                    == TowerUpgradeResult.UPGRADE_REQUIREMENTS_NOT_MET, "Generic upgrade cannot bypass the slider token.");
            var dialog = kim.biryeong.semiontd.ui.PokerTableDialog.create(table, economy.diamond());
            var control = (net.minecraft.server.dialog.input.NumberRangeInput) dialog.common().inputs().getFirst().control();
            require(control.rangeInfo().start() == 200 && control.rangeInfo().end() == 1000,
                    "The native slider must expose 200..1000.");
            var action = dialog.actions().getFirst().action().orElseThrow();
            var click = (net.minecraft.network.chat.ClickEvent.RunCommand) action.createAction(Map.of(
                    "bet", net.minecraft.server.dialog.action.Action.ValueGetter.of("500"))).orElseThrow();
            require(click.command().endsWith(" 500") && click.command().contains(table.betToken().toString()),
                    "Slider action must substitute the selected amount and preserve its table token.");
            long paid = table.paidMineralCost();
            require(ProductionTowerService.betPoker(game, owner, position, table.betToken(), 500)
                    == TowerUpgradeResult.SUCCESS, "Valid bet must use shared upgrade accounting.");
            require(economy.diamond() == 1500, "Only the accepted bet may charge exactly 500.");
            Tower remaining = lane.towerAt(position);
            if (remaining instanceof PokerTableTower upgraded) {
                require(upgraded.hasBet() && upgraded.paidMineralCost() == paid, "Bet state must persist without inflating refunds.");
                require(ProductionTowerService.betPoker(game, owner, position, upgraded.betToken(), 500)
                        == TowerUpgradeResult.UPGRADE_REQUIREMENTS_NOT_MET, "A table cannot bet twice.");
            }
            require(ProductionTowerService.betPoker(game, owner, position, table.betToken(), 500)
                    != TowerUpgradeResult.SUCCESS, "Replaying the old dialog cannot charge again.");
            require(economy.diamond() == 1500, "Rejected replays cannot charge.");
            context.succeed();
        } finally {
            game.close();
        }
    }

    @GameTest(maxTicks = 220)
    public void pokerDeathDebuffsAreCumulativeAndExpireWithoutChangingOtherTargets(GameTestHelper context) {
        ProductionTowerCatalogs.reloadBuiltIns(TowerBalanceConfig.defaultConfig());
        UUID owner = stableUuid("poker-death");
        PlayerLane lane = testLane(context, owner);
        TeamLaneGroup group = new TeamLaneGroup(TeamId.RED, BossMonster.defaultBoss(TeamId.RED));
        group.addLane(lane);
        prepareFloor(context);
        PokerTableTower table = poker(owner, floor(context, 5, 2, 5));
        lane.addTower(table);
        table.resolveHand(lane, 1000, GamblePoker.evaluate(0, 13, 26), bound -> bound - 1);
        require(close(table.currentMaxHealth(), 180 + 60000.0 * 3 / 170), "Health must use the accepted score conversion.");
        double maxHealth = table.currentMaxHealth();
        table.resetForRound(lane);
        table.refreshType(TowerBalanceRuntime.resolve(GambleTowers.POKER_TABLE), lane);
        require(table.hasBet() && close(table.currentMaxHealth(), maxHealth), "Round reset and reload must retain the result.");
        SemionTowerEntity source = entity(lane, table);
        require(source.hasBilModelHolder(), "Poker table must load its BIL model.");
        source.applyTimedEffect(TimedEffectType.TOWER_FLAT_RANGE_BONUS, 5.0, 20);
        source.applyTimedEffect(TimedEffectType.TOWER_FLAT_DAMAGE_BONUS, 100.0, 20);
        require(close(source.attackRange(), 0) && !table.canUseBasicAttacks() && !table.canChaseTargets(),
                "Support buffs must not turn a poker table into a basic attacker.");
        SemionMonsterEntity target = spawnTarget(context, lane, source.position().add(1, 0, 0), "poker-near", 2000);
        SemionMonsterEntity far = spawnTarget(context, lane, source.position().add(3, 0, 0), "poker-far");
        double previous = target.getHealth();
        lane.killTower(table);
        double after = target.getHealth();
        require(Math.abs(previous - after - maxHealth * 0.1) < 0.001, "Death explosion must deal 10% maximum health.");
        require(close(far.getHealth(), 100), "Targets outside 2.5 blocks must remain unaffected.");
        for (TimedEffectType effect : List.of(TimedEffectType.MONSTER_ATTACK_DAMAGE_REDUCTION,
                TimedEffectType.MONSTER_ATTACK_SPEED_REDUCTION, TimedEffectType.MONSTER_ARMOR_REDUCTION)) {
            require(close(target.activeTimedEffectMagnitude(effect), 0.2), "Trips must apply all three 20% debuffs.");
            require(target.activeTimedEffectTicks(effect) == 160, "Death debuffs must last eight seconds.");
        }
        table.notifyDeath(lane);
        require(close(target.getHealth(), after), "Duplicate death notification cannot explode again.");
        context.runAfterDelay(161, () -> {
            try {
                for (TimedEffectType effect : List.of(TimedEffectType.MONSTER_ATTACK_DAMAGE_REDUCTION,
                        TimedEffectType.MONSTER_ATTACK_SPEED_REDUCTION, TimedEffectType.MONSTER_ARMOR_REDUCTION)) {
                    require(close(target.activeTimedEffectMagnitude(effect), 0), "All death debuffs must expire.");
                }
                context.succeed();
            } finally {
                target.discard();
                far.discard();
                group.closeRuntime();
            }
        });
    }

    private static PokerTableTower poker(UUID owner, GridPosition position) {
        return new PokerTableTower(TowerBalanceRuntime.resolve(GambleTowers.POKER_TABLE),
                owner, TeamId.RED, 1, position, position);
    }

    @GameTest(maxTicks = 120)
    public void gamblerBasicAttackTracksPhysicalAndMagicDamageAndMagicGrowth(GameTestHelper context) {
        TowerBalanceConfig defaults = TowerBalanceConfig.defaultConfig();
        TowerBalanceRuntime.apply(defaults);
        UUID owner = stableUuid("gamble-mixed-damage");
        PlayerLane lane = testLane(context, owner);
        TeamLaneGroup group = new TeamLaneGroup(TeamId.RED, BossMonster.defaultBoss(TeamId.RED));
        group.addLane(lane);
        prepareFloor(context);
        SemionMonsterEntity target = null;
        try {
            GamblerTower gambler = gambler(owner, floor(context, 4, 2, 3));
            lane.addTower(gambler);
            gambler.markWaveStarted(1);
            SemionTowerEntity source = entity(lane, gambler);
            target = spawnTarget(context, lane, source.position().add(0, 0, 2), "mixed-target");
            require(close(source.attackDamageAmount(target), 10), "Base attack must total 5 physical plus 5 magic.");
            source.damageTargetResult(target, source.attackDamageAmount(target));
            require(close(gambler.roundPhysicalDamageDealt(), 5), "Physical damage must be recorded separately.");
            require(close(gambler.roundMagicDamageDealt(), 5), "Magic damage must be recorded separately.");
            gambler.setData(GamblerTower.STATE,
                    gambler.state().recordStat(GambleStat.MAGIC_DAMAGE, 35, 5, 70, "magic growth"));
            gambler.onStateChanged(lane);
            source.damageTargetResult(target, source.attackDamageAmount(target));
            require(close(gambler.roundPhysicalDamageDealt(), 10), "Magic growth must not raise physical damage.");
            require(close(gambler.roundMagicDamageDealt(), 45), "Magic growth must add the same +35 as physical growth.");
            require(close(target.runtimeMonster().health(), 45), "Both damage components must reach runtime health.");
            context.succeed();
        } finally {
            if (target != null) target.discard();
            group.closeRuntime();
            TowerBalanceRuntime.apply(defaults);
        }
    }

    @GameTest(maxTicks = 120)
    public void diceRollSwitchesBilOrientationAndPreservesItAcrossUpgrade(GameTestHelper context) {
        TowerBalanceRuntime.apply(TowerBalanceConfig.defaultConfig());
        UUID owner = stableUuid("gamble-dice-model");
        PlayerLane lane = testLane(context, owner);
        TeamLaneGroup group = new TeamLaneGroup(TeamId.RED, BossMonster.defaultBoss(TeamId.RED));
        group.addLane(lane);
        prepareFloor(context);
        try {
            GambleSupportTower dice = support(GambleTowers.DICE_T1, owner, floor(context, 4, 2, 3));
            lane.addTower(dice);
            SemionTowerEntity source = entity(lane, dice);
            for (int face = 1; face <= 6; face++) {
                long seed = 0;
                while (net.minecraft.util.RandomSource.create(seed).nextInt(6) != face - 1) seed++;
                source.getRandom().setSeed(seed);
                dice.onWaveStarted(lane, face);
                require(dice.lastRollCounts()[face - 1] == 1, "The visual must use the actual round roll.");
                require(source.blockbenchModelId().equals("semion-td:tower/gamble_dice_" + face),
                        "The rolled face must select the matching orientation resource.");
                require(source.hasBilModelHolder(), "Each orientation must create a real BIL holder.");
                require(source.getPolymerEntityType(null) == net.minecraft.world.entity.EntityType.BLOCK_DISPLAY,
                        "Dice must use the BIL display path.");
                require(source.runtimeTower() == dice, "Changing the model must retain tower interaction ownership.");
            }
            GambleSupportTower upgraded = support(GambleTowers.DICE_T2, owner, dice.originalPosition());
            upgraded.copyFrom(dice, 100);
            require(lane.replaceTower(dice, upgraded), "Dice upgrade must replace the tower through the shared path.");
            require(entity(lane, upgraded).blockbenchModelId().endsWith("_6"), "Upgrade must retain the rolled face.");
            require(entity(lane, upgraded).blockbenchModelId().equals("semion-td:tower/gamble_dice_t2_6"),
                    "T2 must use its own premium model and retain face six.");
            GambleSupportTower tierThree = support(GambleTowers.DICE_T3, owner, upgraded.originalPosition());
            tierThree.copyFrom(upgraded, 200);
            require(lane.replaceTower(upgraded, tierThree), "T3 upgrade must use the shared replacement path.");
            require(entity(lane, tierThree).blockbenchModelId().equals("semion-td:tower/gamble_dice_t3_6")
                    && entity(lane, tierThree).hasBilModelHolder(), "T3 must load its premium rolled model.");
            tierThree.resetForRound(lane);
            require(entity(lane, tierThree).blockbenchModelId().endsWith("_1"), "Preparation resets the die orientation.");
            context.succeed();
        } finally {
            group.closeRuntime();
        }
    }

    @GameTest(maxTicks = 120)
    public void slotUpgradeCharges260AndNeverAddsToSaleValue(GameTestHelper context) {
        ProductionTowerCatalogs.reloadBuiltIns(TowerBalanceConfig.defaultConfig());
        UUID owner = stableUuid("gamble-slot-cost");
        SemionGame game = startedGambleGame(context, owner, "gamble-slot-cost");
        try {
            PlayerLane lane = game.playerLane(owner).orElseThrow();
            GridPosition position = emptyPosition(lane);
            GamblerTower before = gambler(owner, position);
            lane.addTower(before);
            game.players().get(owner).economy().addMineral(1_000);
            long money = game.players().get(owner).economy().mineral();
            long paid = before.paidMineralCost();
            lane.addTower(support(GambleTowers.SPECTATOR_T1, owner, emptyPosition(lane)));
            require(ProductionTowerService.upgradeTower(game, owner, position, GambleBet.SLOTS.upgradeId())
                    == TowerUpgradeResult.SUCCESS, "Slot bet must use the normal upgrade service.");
            GamblerTower after = (GamblerTower) lane.towers().stream()
                    .filter(t -> t.originalPosition().equals(position)).findFirst().orElseThrow();
            require(game.players().get(owner).economy().mineral() == money - 260, "Slot must charge exactly 260.");
            require(after.paidMineralCost() == paid, "Slot cost must not inflate sale refunds.");
            require(after.state().totalBets() == 1 && after.gambleScore() > 0, "Slot must record one positive result.");
            require(after.state().lastResult().contains("["), "Result must display all three reel symbols.");
            context.succeed();
        } finally {
            game.close();
        }
    }

    private static final List<TimedEffectType> SUPPORT_EFFECTS = List.of(
            TimedEffectType.TOWER_FLAT_RANGE_BONUS,
            TimedEffectType.TOWER_FLAT_RANGE_REDUCTION,
            TimedEffectType.TOWER_HEALTH_REGEN_PER_SECOND,
            TimedEffectType.TOWER_HEALTH_LOSS_PER_SECOND,
            TimedEffectType.TOWER_FLAT_DAMAGE_BONUS,
            TimedEffectType.TOWER_FLAT_DAMAGE_REDUCTION,
            TimedEffectType.TOWER_FLAT_MAX_HEALTH_BONUS,
            TimedEffectType.TOWER_FLAT_MAX_HEALTH_REDUCTION
    );

    @GameTest(maxTicks = 80)
    public void flatSupportStatsAndRegenerationApplyToTheRuntimeTower(GameTestHelper context) {
        TowerBalanceConfig defaults = TowerBalanceConfig.defaultConfig();
        TowerBalanceRuntime.apply(defaults);
        ProductionTowerCatalogs.reloadBuiltIns(defaults);
        UUID owner = stableUuid("gamble-flat-support-owner");
        PlayerLane lane = testLane(context, owner);
        TeamLaneGroup group = new TeamLaneGroup(TeamId.RED, BossMonster.defaultBoss(TeamId.RED));
        group.addLane(lane);
        prepareFloor(context);
        GamblerTower target = gambler(owner, floor(context, 4, 2, 4));
        ResourceLocation rangeSource = supportTestSource("range");
        ResourceLocation regenerationSource = supportTestSource("regeneration");
        ResourceLocation damageSource = supportTestSource("damage");
        ResourceLocation healthSource = supportTestSource("health");
        try {
            lane.addTower(target);
            SemionTowerEntity entity = entity(lane, target);
            entity.setPersistentEffect(TimedEffectType.TOWER_FLAT_RANGE_BONUS, rangeSource, 0.5);
            entity.setPersistentEffect(TimedEffectType.TOWER_HEALTH_REGEN_PER_SECOND, regenerationSource, 5.0);
            entity.setPersistentEffect(TimedEffectType.TOWER_FLAT_DAMAGE_BONUS, damageSource, 5.0);
            entity.setPersistentEffect(TimedEffectType.TOWER_FLAT_MAX_HEALTH_BONUS, healthSource, 50.0);

            require(close(entity.attackRange(), 7.0), "A range roll must add exactly 0.5 blocks.");
            require(close(entity.attackDamageAmount(null), 15.0), "A damage roll must add exactly 5 damage.");
            require(close(target.currentMaxHealth(), 160.0), "A max-health roll must add exactly 50 health.");
            target.syncHealth(100.0);
            entity.setHealth(100.0F);

            context.runAfterDelay(20, () -> {
                try {
                    require(target.health() >= 104.75 && target.health() <= 105.25,
                            "Five health per second must heal about five health over twenty ticks: "
                                    + target.health());
                    entity.setPersistentEffect(TimedEffectType.TOWER_FLAT_RANGE_BONUS, rangeSource, 0.0);
                    entity.setPersistentEffect(TimedEffectType.TOWER_HEALTH_REGEN_PER_SECOND, regenerationSource, 0.0);
                    entity.setPersistentEffect(TimedEffectType.TOWER_FLAT_DAMAGE_BONUS, damageSource, 0.0);
                    entity.setPersistentEffect(TimedEffectType.TOWER_FLAT_MAX_HEALTH_BONUS, healthSource, 0.0);
                    require(close(entity.attackRange(), 6.5), "Removing support must restore base range.");
                    require(close(entity.attackDamageAmount(null), 10.0), "Removing support must restore base damage.");
                    require(close(target.currentMaxHealth(), 110.0), "Removing support must restore base max health.");
                    context.succeed();
                } catch (Throwable failure) {
                    context.fail(Component.literal("Gamble flat support GameTest failed: "
                            + failure.getClass().getName() + ": " + failure.getMessage()));
                } finally {
                    group.closeRuntime();
                    TowerBalanceRuntime.apply(defaults);
                }
            });
        } catch (Throwable failure) {
            group.closeRuntime();
            TowerBalanceRuntime.apply(defaults);
            context.fail(Component.literal("Gamble flat support setup failed: "
                    + failure.getClass().getName() + ": " + failure.getMessage()));
        }
    }

    @GameTest(maxTicks = 80)
    public void negativeSupportUsesFixedStatsAndHealthLossIsNonlethal(GameTestHelper context) {
        TowerBalanceConfig defaults = TowerBalanceConfig.defaultConfig();
        TowerBalanceRuntime.apply(defaults);
        ProductionTowerCatalogs.reloadBuiltIns(defaults);
        UUID owner = stableUuid("gamble-negative-support-owner");
        PlayerLane lane = testLane(context, owner);
        TeamLaneGroup group = new TeamLaneGroup(TeamId.RED, BossMonster.defaultBoss(TeamId.RED));
        group.addLane(lane);
        prepareFloor(context);
        GamblerTower target = gambler(owner, floor(context, 4, 2, 4));
        try {
            lane.addTower(target);
            SemionTowerEntity entity = entity(lane, target);
            entity.setPersistentEffect(TimedEffectType.TOWER_FLAT_RANGE_REDUCTION,
                    supportTestSource("range-loss"), 0.25);
            entity.setPersistentEffect(TimedEffectType.TOWER_HEALTH_LOSS_PER_SECOND,
                    supportTestSource("health-loss"), 1.0);
            entity.setPersistentEffect(TimedEffectType.TOWER_FLAT_DAMAGE_REDUCTION,
                    supportTestSource("damage-loss"), 2.5);
            entity.setPersistentEffect(TimedEffectType.TOWER_FLAT_MAX_HEALTH_REDUCTION,
                    supportTestSource("max-health-loss"), 25.0);
            require(close(entity.attackRange(), 6.25), "Range weakening must subtract exactly 0.25 blocks.");
            require(close(entity.attackDamageAmount(null), 7.5), "Damage weakening must subtract exactly 2.5.");
            require(close(target.currentMaxHealth(), 85.0), "Max-health weakening must subtract exactly 25.");
            target.syncHealth(1.5);
            entity.setHealth(1.5F);
            context.runAfterDelay(20, () -> {
                try {
                    require(close(target.health(), 1.0),
                            "Health loss must stop at one health instead of killing the supported tower.");
                    context.succeed();
                } catch (Throwable failure) {
                    context.fail(Component.literal("Gamble negative support GameTest failed: "
                            + failure.getClass().getName() + ": " + failure.getMessage()));
                } finally {
                    group.closeRuntime();
                    TowerBalanceRuntime.apply(defaults);
                }
            });
        } catch (Throwable failure) {
            group.closeRuntime();
            TowerBalanceRuntime.apply(defaults);
            context.fail(Component.literal("Gamble negative support setup failed: "
                    + failure.getClass().getName() + ": " + failure.getMessage()));
        }
    }

    @GameTest(maxTicks = 80)
    public void spectatorsChooseTheHighestScoreAndLimitThreeLinksPerGambler(GameTestHelper context) {
        TowerBalanceConfig defaults = TowerBalanceConfig.defaultConfig();
        TowerBalanceRuntime.apply(defaults);
        ProductionTowerCatalogs.reloadBuiltIns(defaults);
        UUID owner = stableUuid("gamble-spectator-cap-owner");
        PlayerLane lane = testLane(context, owner);
        TeamLaneGroup group = new TeamLaneGroup(TeamId.RED, BossMonster.defaultBoss(TeamId.RED));
        group.addLane(lane);
        prepareFloor(context);
        GamblerTower strongest = gambler(owner, floor(context, 5, 2, 6));
        strongest.setData(GamblerTower.STATE, GambleState.EMPTY.recordStat(
                GambleStat.DAMAGE, 50, 10, 100, "strongest"));
        GamblerTower runnerUp = gambler(owner, floor(context, 7, 2, 6));
        runnerUp.setData(GamblerTower.STATE, GambleState.EMPTY.recordStat(
                GambleStat.DAMAGE, 25, 10, 50, "runner-up"));
        List<GambleSupportTower> spectators = List.of(
                support(GambleTowers.SPECTATOR_T3, owner, floor(context, 3, 2, 3)),
                support(GambleTowers.SPECTATOR_T3, owner, floor(context, 4, 2, 3)),
                support(GambleTowers.SPECTATOR_T3, owner, floor(context, 5, 2, 3)),
                support(GambleTowers.SPECTATOR_T3, owner, floor(context, 6, 2, 3))
        );
        try {
            lane.addTower(strongest);
            lane.addTower(runnerUp);
            spectators.forEach(lane::addTower);
            for (GambleSupportTower spectator : spectators) {
                require(close(spectator.currentMaxHealth(), 300.0),
                        "Tier-three slot machines must have 300 health.");
                SemionTowerEntity source = entity(lane, spectator);
                require(GambleRoundEffects.assignSpectator(
                        lane, owner, GambleRoundEffects.sourceId(spectator), source, 20.0).isPresent(),
                        "Every spectator must find an available owned gambler.");
            }
            require(GambleRoundEffects.spectatorLinkCount(lane, owner, strongest.originalPosition()) == 3,
                    "Exactly three spectators must occupy the strongest gambler.");
            require(GambleRoundEffects.spectatorLinkCount(lane, owner, runnerUp.originalPosition()) == 1,
                    "The fourth spectator must fall back to the next-highest gamble score.");
            GambleRoundEffects.clearAll(lane, owner);
            require(GambleRoundEffects.spectatorLinkCount(lane, owner, strongest.originalPosition()) == 0
                            && GambleRoundEffects.spectatorLinkCount(
                            lane, owner, runnerUp.originalPosition()) == 0,
                    "Round cleanup must release every spectator assignment.");
            context.succeed();
        } finally {
            group.closeRuntime();
            TowerBalanceRuntime.apply(defaults);
        }
    }

    @GameTest(maxTicks = 120)
    public void supportRollsAreOwnerFilteredStackBySourceAndLiveUntilRoundCleanup(GameTestHelper context) {
        TowerBalanceConfig defaults = TowerBalanceConfig.defaultConfig();
        TowerBalanceRuntime.apply(defaults);
        ProductionTowerCatalogs.reloadBuiltIns(defaults);
        UUID owner = stableUuid("gamble-support-owner");
        UUID otherOwner = stableUuid("gamble-support-other");
        PlayerLane lane = testLane(context, owner);
        TeamLaneGroup group = new TeamLaneGroup(TeamId.RED, BossMonster.defaultBoss(TeamId.RED));
        group.addLane(lane);
        prepareFloor(context);

        GambleSupportTower dice = support(GambleTowers.DICE_T3, owner, floor(context, 3, 2, 3));
        GambleSupportTower spectator = support(GambleTowers.SPECTATOR_T3, owner, floor(context, 5, 2, 3));
        GamblerTower target = gambler(owner, floor(context, 4, 2, 5));
        GamblerTower foreign = gambler(otherOwner, floor(context, 4, 2, 4));
        try {
            lane.addTower(dice);
            lane.addTower(spectator);
            lane.addTower(target);
            lane.addTower(foreign);
            lane.markWaveStarted(1);

            require(dice.affectedTargets() == 1 && spectator.affectedTargets() == 1,
                    "Dice must support owned combat towers while spectators support only the owned gambler.");
            require(dice.linkedTargets() == 1 && spectator.linkedTargets() == 1,
                    "Every affected tower must have a visible connection from its support tower.");
            require(sum(dice.lastRollCounts()) == 1 && sum(spectator.lastRollCounts()) == 1,
                    "Each support tower must roll exactly one face per round, regardless of target count.");
            require(java.util.Arrays.stream(spectator.lastRollCounts()).sum() == 1,
                    "Every spectator tier must use the same one-through-six die.");

            var diceSource = GambleRoundEffects.sourceId(dice);
            var spectatorSource = GambleRoundEffects.sourceId(spectator);
            SemionTowerEntity diceEntity = entity(lane, dice);
            SemionTowerEntity spectatorEntity = entity(lane, spectator);
            SemionTowerEntity targetEntity = entity(lane, target);
            SemionTowerEntity foreignEntity = entity(lane, foreign);
            require(close(diceEntity.attackRange(), 0.0) && close(spectatorEntity.attackRange(), 0.0),
                    "Support entities must have no combat range and therefore never attack.");
            require(GambleRollLabels.hasVisibleLabel(lane, owner, dice)
                            && GambleRollLabels.hasVisibleLabel(lane, owner, spectator),
                    "Each support tower must display its round face above itself.");
            require(GambleRollLabels.count(lane, owner) == 2,
                    "The lane must keep one face label for each support tower.");
            require(sourceCount(targetEntity, diceSource) == dice.activeEffects().size()
                            && sourceCount(targetEntity, spectatorSource) == spectator.activeEffects().size(),
                    "The target must retain every independently sourced stat result from each support.");
            require(sourceCount(diceEntity, diceSource) == 0 && sourceCount(spectatorEntity, spectatorSource) == 0,
                    "Support towers must exclude themselves from their own roll.");
            require(sourceCount(diceEntity, spectatorSource) == 0
                            && sourceCount(spectatorEntity, diceSource) == 0,
                    "Support towers must never support each other and accidentally gain combat stats.");
            require(sourceCount(foreignEntity, diceSource) == 0 && sourceCount(foreignEntity, spectatorSource) == 0,
                    "A tower with another owner must never receive gamble support.");

            dice.onLaneCleared(lane);
            spectator.onLaneCleared(lane);
            require(GambleRollLabels.count(lane, owner) == 0,
                    "Floating faces must disappear as soon as the owner's lane is cleared.");
            require(sourceCount(targetEntity, diceSource) == dice.activeEffects().size()
                            && sourceCount(targetEntity, spectatorSource) == spectator.activeEffects().size(),
                    "Clearing the lane must hide the faces without ending surviving support effects early.");

            require(lane.killTower(dice), "The dice tower must be destroyable for persistence coverage.");
            require(sourceCount(targetEntity, diceSource) == 0
                            && sourceCount(targetEntity, spectatorSource) == spectator.activeEffects().size(),
                    "A destroyed support tower must immediately remove only its own result.");
            GambleRoundEffects.clearAll(lane, owner);
            require(GambleRollLabels.count(lane, owner) == 0,
                    "Round cleanup must remove every floating face label.");
            require(sourceCount(targetEntity, diceSource) == 0 && sourceCount(targetEntity, spectatorSource) == 0,
                    "Round cleanup must remove every gamble source exactly.");

            lane.resetForRound();
            lane.markWaveStarted(2);
            SemionTowerEntity nextTarget = entity(lane, target);
            require(sourceCount(nextTarget, diceSource) == dice.activeEffects().size()
                            && sourceCount(nextTarget, spectatorSource) == spectator.activeEffects().size(),
                    "The next round must produce fresh effects after the destroyed support respawns.");
            GambleRoundEffects.clearAll(lane, owner);
            require(sourceCount(nextTarget, diceSource) == 0 && sourceCount(nextTarget, spectatorSource) == 0,
                    "Elimination cleanup must share the exact round cleanup behavior.");
            context.succeed();
        } finally {
            group.closeRuntime();
            TowerBalanceRuntime.apply(defaults);
        }
    }

    @GameTest(maxTicks = 120)
    public void rolledStatePreservesHealthRatioAndBasicSplashDamage(GameTestHelper context) {
        TowerBalanceConfig defaults = TowerBalanceConfig.defaultConfig();
        TowerBalanceRuntime.apply(defaults);
        UUID owner = stableUuid("gamble-combat-owner");
        PlayerLane lane = testLane(context, owner);
        TeamLaneGroup group = new TeamLaneGroup(TeamId.RED, BossMonster.defaultBoss(TeamId.RED));
        group.addLane(lane);
        prepareFloor(context);
        GamblerTower original = gambler(owner, floor(context, 4, 2, 3));
        SemionMonsterEntity primary = null;
        SemionMonsterEntity secondary = null;
        SemionMonsterEntity splashTarget = null;
        try {
            lane.addTower(original);
            GambleState upgradedState = new GambleState(
                    50.0, 35.0, 0.0, 0.5, 0.5,
                    120.0, Set.of(), 4, "능력치 테스트"
            );
            original.setData(GamblerTower.STATE, upgradedState);
            original.syncMaxHealth(160.0, false);
            original.syncHealth(80.0);
            entity(lane, original).setHealth(80.0F);

            GamblerTower replacement = gambler(owner, original.position());
            replacement.copyFrom(original, 0);
            require(lane.replaceTower(original, replacement), "Self-upgrade replacement must succeed.");
            require(close(replacement.currentMaxHealth(), 160.0) && close(replacement.health(), 80.0),
                    "A fixed max-health upgrade must preserve the exact 50% health ratio.");
            require(close(replacement.adjustAttackRange(6.5), 7.0),
                    "The range result must add the rolled amount to the base range.");
            require(close(replacement.modifyAttackDamage(null, null, replacement.type().damage()), 45.0),
                    "The damage result must combine 5 physical, 35 physical growth, and 5 magic damage.");
            require(close(replacement.splashRadius(), 2.5),
                    "The basic splash radius must remain fixed despite legacy rolled state.");

            SemionTowerEntity source = entity(lane, replacement);
            ResourceLocation supportDamage = supportTestSource("grown-damage-composition");
            source.setPersistentEffect(TimedEffectType.TOWER_FLAT_DAMAGE_BONUS, supportDamage, 2.5);
            require(close(source.attackDamageAmount(null), 47.5),
                    "Fixed support damage must be added after the gambler's +35 growth without being multiplied.");
            source.setPersistentEffect(TimedEffectType.TOWER_FLAT_DAMAGE_BONUS, supportDamage, 0.0);
            primary = spawnTarget(context, lane, source.position().add(0.0, 0.0, 2.0), "gamble-primary");
            splashTarget = spawnTarget(context, lane, primary.position().add(0.5, 0.0, 0.0), "gamble-splash");
            secondary = spawnTarget(context, lane, primary.position().add(2.75, 0.0, 0.0), "gamble-secondary");
            replacement.onAttackResolved(source, primary, 100.0, 100.0, 100.0, false);
            require(close(splashTarget.runtimeMonster().health(), 40.0),
                    "Every basic attack must deal 60% finalized damage inside the fixed splash radius.");
            require(close(secondary.runtimeMonster().health(), 100.0),
                    "A target outside the basic splash radius must not take splash damage.");
            context.succeed();
        } finally {
            if (primary != null) primary.discard();
            if (secondary != null) secondary.discard();
            if (splashTarget != null) splashTarget.discard();
            group.closeRuntime();
            TowerBalanceRuntime.apply(defaults);
        }
    }

    @GameTest(maxTicks = 120)
    public void scoreThresholdPromotionsKeepStateAndEquipFinalFormItems(GameTestHelper context) {
        TowerBalanceConfig defaults = TowerBalanceConfig.defaultConfig();
        TowerBalanceRuntime.apply(defaults);
        ProductionTowerCatalogs.reloadBuiltIns(defaults);
        UUID owner = stableUuid("gamble-promotion-owner");
        PlayerLane lane = testLane(context, owner);
        TeamLaneGroup group = new TeamLaneGroup(TeamId.RED, BossMonster.defaultBoss(TeamId.RED));
        group.addLane(lane);
        prepareFloor(context);
        GamblerTower kingCandidate = gambler(owner, floor(context, 4, 2, 3));
        GamblerTower darkCandidate = gambler(owner, floor(context, 8, 2, 3));
        try {
            lane.addTower(kingCandidate);
            lane.addTower(darkCandidate);
            GambleState kingState = new GambleState(
                    50.0, 5.0, 0.0, 0.5, 0.0,
                    400.0, Set.of(GambleAbility.LOSS_INSURANCE), 12, "도박왕 전직 테스트"
            );
            GambleState darkState = new GambleState(
                    -20.0, -2.0, 0.0, -0.5, 0.0,
                    -200.0, Set.of(), 4, "어둠의 도박왕 전직 테스트"
            );
            kingCandidate.setData(GamblerTower.STATE, kingState);
            darkCandidate.setData(GamblerTower.STATE, darkState);
            TowerUpgradeOption bet = ProductionTowerCatalog.upgrade(
                    GambleTowers.GAMBLER, GambleBet.ODD.upgradeId()).orElseThrow(() ->
                    new IllegalStateException("The gambler odd-bet upgrade is missing after catalog reload."));

            kingCandidate.onUpgradeCompleted(lane, kingCandidate, bet);
            darkCandidate.onUpgradeCompleted(lane, darkCandidate, bet);

            Tower kingTower = lane.towerAt(kingCandidate.position());
            Tower darkTower = lane.towerAt(darkCandidate.position());
            require(kingTower instanceof GamblerTower && kingTower.type().id().equals(GambleTowers.KING.id()),
                    "A +400 score gambler must become the Gamble King.");
            require(darkTower instanceof GamblerTower
                            && darkTower.type().id().equals(GambleTowers.DARK_KING.id()),
                    "A -200 score gambler must become the Dark Gamble King.");
            GamblerTower king = (GamblerTower) kingTower;
            GamblerTower dark = (GamblerTower) darkTower;
            require(king.state().equals(kingState) && dark.state().equals(darkState),
                    "Promotion must preserve every gamble stat, score, bet count, result, and ability.");
            require(close(king.currentMaxHealth(), 450.0) && close(dark.currentMaxHealth(), 420.0),
                    "Promoted base health must retain the previous fixed gamble deltas.");
            require(close(king.splashRadius(), 3.0) && close(dark.splashRadius(), 3.25),
                    "Final forms must gain their configured splash-radius bonuses.");
            require(entity(lane, king).getItemBySlot(EquipmentSlot.MAINHAND).is(Items.DIAMOND),
                    "The Gamble King must hold a diamond.");
            require(entity(lane, dark).getItemBySlot(EquipmentSlot.MAINHAND).is(Items.NETHERITE_INGOT),
                    "The Dark Gamble King must hold a netherite ingot.");
            context.succeed();
        } finally {
            group.closeRuntime();
            TowerBalanceRuntime.apply(defaults);
        }
    }

    @GameTest(maxTicks = 120)
    public void forcedMatchCloseClearsEconomyBeforeTheSamePlayerStartsAgain(GameTestHelper context) {
        TowerBalanceConfig defaults = TowerBalanceConfig.defaultConfig();
        TowerBalanceRuntime.apply(defaults);
        UUID owner = stableUuid("gamble-close-lifecycle-owner");
        SemionGame first = null;
        SemionGame second = null;
        try {
            first = startedGambleGame(context, owner, "gamble-first-match");
            var firstPlayer = first.players().get(owner);
            firstPlayer.job().orElseThrow().onRoundStarted(new JobContext(first, firstPlayer), 1);
            var firstEconomy = firstPlayer.economy();
            long firstDiamond = firstEconomy.diamond();
            require(GambleSpectatorRewards.hasActiveEconomy(owner),
                    "Starting a gamble round must register the active economy.");

            first.close();
            first = null;
            require(!GambleSpectatorRewards.hasActiveEconomy(owner),
                    "Forced match close must remove the active gamble economy.");

            second = startedGambleGame(context, owner, "gamble-second-match");
            var secondPlayer = second.players().get(owner);
            secondPlayer.job().orElseThrow().onRoundStarted(new JobContext(second, secondPlayer), 1);
            long secondDiamond = secondPlayer.economy().diamond();
            require(GambleSpectatorRewards.awardFaceSix(
                    owner, GambleTowers.SPECTATOR_T1, 6) == 5,
                    "The second match must register a fresh economy for the same UUID.");
            require(firstEconomy.diamond() == firstDiamond,
                    "The closed first match economy must never receive the second match reward.");
            require(secondPlayer.economy().diamond() == secondDiamond + 5,
                    "The second match economy must receive the face-six reward exactly once.");

            second.close();
            second = null;
            require(!GambleSpectatorRewards.hasActiveEconomy(owner),
                    "Closing the second match must leave no active gamble economy.");
            context.succeed();
        } finally {
            if (first != null) first.close();
            if (second != null) second.close();
            GambleSpectatorRewards.closeRound(owner);
            TowerBalanceRuntime.apply(defaults);
        }
    }

    @GameTest(maxTicks = 80)
    public void maximumScoreClosesEveryBetBeforeChargingDiamonds(GameTestHelper context) {
        TowerBalanceConfig defaults = TowerBalanceConfig.defaultConfig();
        TowerBalanceRuntime.apply(defaults);
        ProductionTowerCatalogs.reloadBuiltIns(defaults);
        UUID owner = stableUuid("gamble-score-cap-owner");
        SemionGame game = startedGambleGame(context, owner, "gamble-score-cap");
        try {
            PlayerLane lane = game.playerLane(owner).orElseThrow();
            GridPosition position = emptyPosition(lane);
            GamblerTower gambler = new GamblerTower(
                    TowerBalanceRuntime.resolve(GambleTowers.GAMBLER), owner, TeamId.RED, 1,
                    position, position);
            gambler.setData(GamblerTower.STATE, new GambleState(
                    10_000.0, 1_000.0, 0.0, 100.0, 0.0,
                    500.0, Set.of(GambleAbility.LOSS_INSURANCE), 99, "최대 점수"
            ));
            lane.addTower(gambler);
            game.players().get(owner).economy().addMineral(1_000);
            long before = game.players().get(owner).economy().mineral();

            require(ProductionTowerService.availableUpgrades(game, owner, position).isEmpty(),
                    "All bet buttons must disappear at the maximum score.");
            for (GambleBet bet : GambleBet.values()) {
                require(ProductionTowerService.upgradeTower(game, owner, position, bet.upgradeId())
                                == TowerUpgradeResult.UPGRADE_REQUIREMENTS_NOT_MET,
                        "Every direct bet request must be rejected after the buttons close.");
            }
            require(game.players().get(owner).economy().mineral() == before,
                    "Rejected capped gambling must not charge diamonds.");
            context.succeed();
        } finally {
            game.close();
            TowerBalanceRuntime.apply(defaults);
        }
    }

    @GameTest(maxTicks = 80)
    public void balanceReloadReclampsAndPromotesExistingGamblerWithoutCharging(GameTestHelper context) {
        TowerBalanceConfig defaults = TowerBalanceConfig.defaultConfig();
        TowerBalanceConfig legacy = withGambleScores(defaults, 1_000.0, 2_000.0);
        ProductionTowerCatalogs.reloadBuiltIns(legacy);
        UUID owner = stableUuid("gamble-reload-cap-owner");
        SemionGame game = startedGambleGame(context, owner, "gamble-reload-cap");
        try {
            PlayerLane lane = game.playerLane(owner).orElseThrow();
            GridPosition position = emptyPosition(lane);
            GamblerTower gambler = new GamblerTower(
                    TowerBalanceRuntime.resolve(GambleTowers.GAMBLER), owner, TeamId.RED, 1,
                    position, position);
            gambler.setData(GamblerTower.STATE, new GambleState(
                    4_000.0, 400.0, 0.0, 40.0, 20.0,
                    600.0, Set.of(GambleAbility.LOSS_INSURANCE), 30, "이전 상한"
            ));
            lane.addTower(gambler);
            gambler.syncHealth(gambler.currentMaxHealth() * 0.5);
            game.players().get(owner).economy().addMineral(1_000);
            long before = game.players().get(owner).economy().mineral();

            ProductionTowerCatalogs.reloadBuiltIns(defaults);
            game.refreshProductionTowerTypes();

            Tower refreshed = lane.towerAt(position);
            require(refreshed instanceof GamblerTower
                            && refreshed.type().id().equals(GambleTowers.KING.id()),
                    "Lowering the promotion threshold must promote an existing eligible gambler.");
            GamblerTower king = (GamblerTower) refreshed;
            require(close(king.state().cumulativeScore(), 500.0),
                    "Reload must clamp the displayed score to the new maximum.");
            require(close(king.state().maxHealthDelta(), 2_500.0)
                            && close(king.state().damageDelta(), 250.0)
                            && close(king.state().rangeDelta(), 25.0),
                    "Reload must clamp every stored positive stat to the new score-equivalent maximum.");
            require(close(king.health(), king.currentMaxHealth() * 0.5),
                    "Reload and promotion must preserve the current health ratio.");
            require(king.runtimeDetailLines().stream().anyMatch(line -> line.contains("+500.0 / +500.0")),
                    "Runtime details must immediately show the reloaded cap.");
            require(ProductionTowerService.availableUpgrades(game, owner, position).isEmpty(),
                    "Reload must immediately close every gamble button at the new cap.");
            require(ProductionTowerService.upgradeTower(
                            game, owner, position, GambleBet.TWO_DICE.upgradeId())
                            == TowerUpgradeResult.UPGRADE_REQUIREMENTS_NOT_MET,
                    "A direct bet request must be rejected after a cap-lowering reload.");
            require(game.players().get(owner).economy().mineral() == before,
                    "A rejected post-reload bet must not charge diamonds.");
            context.succeed();
        } finally {
            game.close();
            ProductionTowerCatalogs.reloadBuiltIns(defaults);
        }
    }

    private static GambleSupportTower support(kim.biryeong.semiontd.tower.TowerType type,
                                               UUID owner, GridPosition position) {
        return new GambleSupportTower(TowerBalanceRuntime.resolve(type), owner, TeamId.RED, 1, position, position);
    }

    private static GamblerTower gambler(UUID owner, GridPosition position) {
        return new GamblerTower(TowerBalanceRuntime.resolve(GambleTowers.GAMBLER),
                owner, TeamId.RED, 1, position, position);
    }

    private static SemionGame startedGambleGame(
            GameTestHelper context, UUID owner, String playerName
    ) {
        SemionGame game = new SemionGame(
                EconomyConfig.defaultConfig(), WaveConfig.defaultConfig(),
                SyntheticArenaFactory.create(context.getLevel(), context.absolutePos(BlockPos.ZERO))
        );
        require(game.selectJob(owner, kim.biryeong.semiontd.job.GambleTowerJob.ID),
                "Gamble job selection must succeed.");
        require(game.start(
                context.getLevel().getServer(),
                new ParticipantSelectionPlan(
                        MatchMode.NORMAL,
                        List.of(new AssignedParticipant(owner, playerName, TeamId.RED, 1)),
                        Set.of(), 1
                )
        ), "Gamble test game must start.");
        return game;
    }

    private static GridPosition emptyPosition(PlayerLane lane) {
        var bounds = lane.laneLayout().laneArea();
        for (int x = bounds.min().getX(); x <= bounds.max().getX(); x++) {
            for (int z = bounds.min().getZ(); z <= bounds.max().getZ(); z++) {
                BlockPos candidate = new BlockPos(x, bounds.min().getY(), z);
                GridPosition position = GridPosition.from(candidate);
                if (lane.canPlaceTowerAt(candidate) && !lane.hasTowerAt(position)) {
                    return position;
                }
            }
        }
        throw new AssertionError("No empty Gamble tower position was found.");
    }

    private static SemionMonsterEntity spawnTarget(
            GameTestHelper context, PlayerLane lane, Vec3 position, String id
    ) {
        return spawnTarget(context, lane, position, id, 100.0);
    }

    private static SemionMonsterEntity spawnTarget(
            GameTestHelper context, PlayerLane lane, Vec3 position, String id, double maxHealth
    ) {
        Monster runtime = new Monster(id, TeamId.RED, 1, Optional.empty(), Optional.empty(),
                maxHealth, 0.0, 1.0, AttackKind.MELEE, "minecraft:zombie", 0L);
        SemionMonsterEntity entity = new SemionMonsterEntity(SemionEntityTypes.MONSTER, context.getLevel());
        entity.configureFrom(runtime, lane.laneLayout());
        entity.setNoAi(true);
        entity.setPos(position.x, position.y, position.z);
        require(context.getLevel().addFreshEntity(entity), "Gamble target must spawn.");
        runtime.markMinecraftEntitySpawned(entity.getId(), position.x, position.y, position.z);
        lane.activeMonsters().add(runtime);
        return entity;
    }

    private static int sourceCount(SemionTowerEntity entity, net.minecraft.resources.ResourceLocation source) {
        return (int) SUPPORT_EFFECTS.stream().filter(type -> entity.hasTimedEffectSource(type, source)).count();
    }

    private static ResourceLocation supportTestSource(String path) {
        return ResourceLocation.fromNamespaceAndPath("semion-td", "gamble/test/" + path);
    }

    private static int sum(int[] values) {
        return java.util.Arrays.stream(values).sum();
    }

    private static SemionTowerEntity entity(PlayerLane lane, Tower tower) {
        return GambleRoundEffects.towerEntity(tower, lane).orElseThrow(() ->
                new IllegalStateException("The runtime entity is missing for tower " + tower.type().id()
                        + " at " + tower.position() + "."));
    }

    private static PlayerLane testLane(GameTestHelper context, UUID owner) {
        BlockPos min = context.absolutePos(new BlockPos(0, 1, 0));
        BlockPos max = context.absolutePos(new BlockPos(14, 6, 14));
        LaneRegionLayout layout = new LaneRegionLayout(
                1,
                Vec3.atCenterOf(context.absolutePos(new BlockPos(1, 2, 1))),
                List.of(Vec3.atCenterOf(context.absolutePos(new BlockPos(7, 2, 7)))),
                Vec3.atCenterOf(context.absolutePos(new BlockPos(7, 2, 13))),
                BlockBounds.of(min, max),
                List.of(GridPosition.from(context.absolutePos(new BlockPos(10, 2, 11))))
        );
        return new PlayerLane(TeamId.RED, 1, owner, context.getLevel(), layout);
    }

    private static GridPosition floor(GameTestHelper context, int x, int y, int z) {
        return GridPosition.from(context.absolutePos(new BlockPos(x, y, z)));
    }

    private static void prepareFloor(GameTestHelper context) {
        for (int x = 1; x <= 12; x++) {
            for (int z = 1; z <= 12; z++) {
                BlockPos floor = context.absolutePos(new BlockPos(x, 2, z));
                context.getLevel().setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
                context.getLevel().setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static UUID stableUuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static TowerBalanceConfig withGambleScores(
            TowerBalanceConfig defaults, double kingPromotionScore, double maxGambleScore
    ) {
        LinkedHashMap<String, Map<String, Double>> abilities = new LinkedHashMap<>(defaults.abilities());
        LinkedHashMap<String, Double> gamble = new LinkedHashMap<>(abilities.get(GambleBalance.GLOBAL_ID));
        gamble.put("kingPromotionScore", kingPromotionScore);
        gamble.put("maxGambleScore", maxGambleScore);
        abilities.put(GambleBalance.GLOBAL_ID, gamble);
        return new TowerBalanceConfig(defaults.towers(), defaults.upgradeCosts(), abilities,
                defaults.illusionCloneQueue(), defaults.villagerAdv(), defaults.schemaVersion());
    }

    private static boolean close(double first, double second) {
        return Math.abs(first - second) < 0.0001;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
