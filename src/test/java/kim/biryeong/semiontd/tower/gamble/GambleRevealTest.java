package kim.biryeong.semiontd.tower.gamble;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class GambleRevealTest {
    @Test
    void cardsRevealOneAtATimeWithExactlyThreeDrawSounds() {
        var reveal = new GambleReveal(GambleReveal.Kind.CARDS, List.of(0, 17, 51), "포커", "플러시", "플러시", true);
        assertEquals(List.of(-1, -1, -1), reveal.frameAt(0).glyphs());
        assertEquals(List.of(0, -1, -1), reveal.frameAt(12).glyphs());
        assertEquals(List.of(0, 17, -1), reveal.frameAt(24).glyphs());
        int draws = 0;
        for (int age = 0; age < reveal.durationTicks(); age++) {
            if (reveal.frameAt(age).cue() == GambleReveal.Cue.DRAW) draws++;
        }
        assertEquals(3, draws);
        assertTrue(reveal.frameAt(36).revealed());
        assertEquals(reveal.outcomes(), reveal.frameAt(70).glyphs());
    }

    @Test
    void slotsAndDiceSettleOnThePrecomputedResult() {
        for (var reveal : List.of(
                new GambleReveal(GambleReveal.Kind.SLOTS, List.of(5, 3, 0), "슬롯", "결과", "결과", true),
                new GambleReveal(GambleReveal.Kind.DICE, List.of(6, 2), "주사위", "결과", "결과", true),
                new GambleReveal(GambleReveal.Kind.DICE, List.of(1), "홀수", "결과", "결과", false))) {
            int stops = 0;
            int rolling = 0;
            for (int age = 0; age < reveal.durationTicks(); age++) {
                var frame = reveal.frameAt(age);
                if (frame.cue() == GambleReveal.Cue.STOP) stops++;
                if (frame.cue() == GambleReveal.Cue.ROLL) rolling++;
                if (frame.revealed()) assertEquals(reveal.outcomes(), frame.glyphs());
            }
            assertEquals(reveal.outcomes().size(), stops);
            assertTrue(rolling > 0);
            assertEquals(reveal.outcomes(), reveal.frameAt(reveal.durationTicks() - 1).glyphs());
        }
    }
}
