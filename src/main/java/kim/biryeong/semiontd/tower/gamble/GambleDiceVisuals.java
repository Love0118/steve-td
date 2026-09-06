package kim.biryeong.semiontd.tower.gamble;

import java.util.List;
import java.util.stream.IntStream;
import kim.biryeong.semiontd.entity.visual.EntityVisual;

/** Six static orientations keep the rolled face upward without a separate animation controller. */
public final class GambleDiceVisuals {
    private static final List<String> MODEL_IDS = IntStream.rangeClosed(1, 6)
            .mapToObj(face -> "semion-td:tower/gamble_dice_" + face).toList();
    private static final List<List<EntityVisual>> VISUALS = List.of(0.75, 0.90, 1.05).stream()
            .map(scale -> MODEL_IDS.stream().map(id -> EntityVisual.builder("minecraft:slime")
                    .blockbenchModel(id).scale(scale).build()).toList()).toList();

    private GambleDiceVisuals() {
    }

    public static List<String> modelIds() {
        return MODEL_IDS;
    }

    public static EntityVisual visual(int tier, int face) {
        if (tier < 1 || tier > 3 || face < 0 || face > 6) {
            throw new IllegalArgumentException("Dice visual requires tier 1..3 and face 0..6.");
        }
        return VISUALS.get(tier - 1).get(Math.max(1, face) - 1);
    }
}
