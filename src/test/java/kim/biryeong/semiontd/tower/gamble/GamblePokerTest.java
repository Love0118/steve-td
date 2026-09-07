package kim.biryeong.semiontd.tower.gamble;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class GamblePokerTest {
    @Test
    void exhaustsEveryThreeCardCombinationWithTheAgreedProbabilitiesAndScores() {
        Map<GamblePoker.Kind, Integer> counts = new EnumMap<>(GamblePoker.Kind.class);
        int destroyed = 0;
        int weak = 0;
        int totalScore = 0;
        for (int a = 0; a < 52; a++) {
            for (int b = a + 1; b < 52; b++) {
                for (int c = b + 1; c < 52; c++) {
                    var hand = GamblePoker.evaluate(a, b, c);
                    counts.merge(hand.kind(), 1, Integer::sum);
                    destroyed += hand.destroyed() ? 1 : 0;
                    weak += hand.weak() ? 1 : 0;
                    totalScore += hand.score();
                    assertEquals(hand.score(), GamblePoker.evaluate(c, a, b).score());
                }
            }
        }
        assertEquals(Map.of(GamblePoker.Kind.HIGH_CARD, 16440, GamblePoker.Kind.PAIR, 3744,
                GamblePoker.Kind.FLUSH, 1096, GamblePoker.Kind.STRAIGHT, 720,
                GamblePoker.Kind.THREE_OF_A_KIND, 52, GamblePoker.Kind.STRAIGHT_FLUSH, 48), counts);
        assertEquals(1800, destroyed);
        assertEquals(4920, weak);
        assertEquals(457064, totalScore);
    }

    @Test
    void aceCanEndOrStartAStraightButCannotWrapFromKingToTwo() {
        assertEquals(GamblePoker.Kind.STRAIGHT, GamblePoker.evaluate(12, 13, 27).kind()); // A,2,3
        assertEquals(GamblePoker.Kind.STRAIGHT, GamblePoker.evaluate(10, 24, 38).kind()); // Q,K,A
        assertEquals(GamblePoker.Kind.HIGH_CARD, GamblePoker.evaluate(11, 25, 26).kind()); // K,A,2
        assertEquals(30, GamblePoker.evaluate(0, 13, 28).score());
        assertEquals(42, GamblePoker.evaluate(12, 25, 28).score());
        assertThrows(IllegalArgumentException.class, () -> GamblePoker.evaluate(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> GamblePoker.evaluate(-1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> GamblePoker.evaluate(0, 1, 52));
    }

    @Test
    void specialAbilitiesRequireBothTheHandAndEnoughWeightedScore() {
        var flush = GamblePoker.evaluate(0, 3, 7);
        var straight = GamblePoker.evaluate(0, 14, 28);
        var triple = GamblePoker.evaluate(0, 13, 26);
        assertEquals(0, flush.debuffCount(999, 50000));
        assertEquals(1, flush.debuffCount(1000, 50000));
        assertEquals(0, straight.debuffCount(909, 50000));
        assertEquals(2, straight.debuffCount(910, 50000));
        assertEquals(0, triple.debuffCount(833, 50000));
        assertEquals(3, triple.debuffCount(834, 50000));
        assertEquals(0, GamblePoker.evaluate(12, 25, 28).debuffCount(1000, 50000));
        assertEquals(60000, triple.weightedScore(1000));
        assertTrue(GamblePoker.validBet(200));
        assertTrue(GamblePoker.validBet(1000));
        assertFalse(GamblePoker.validBet(199));
        assertFalse(GamblePoker.validBet(1001));
        assertThrows(IllegalArgumentException.class, () -> triple.weightedScore(Long.MAX_VALUE));
    }

    @Test
    void drawingUsesShrinkingDeckBoundsWithoutDuplicateCards() {
        int[] index = {0};
        var hand = GamblePoker.draw(bound -> {
            assertEquals(52 - index[0]++, bound);
            return bound - 1;
        });
        assertEquals(3, hand.cards().stream().distinct().count());
        assertEquals(3, index[0]);
    }
}
