package kim.biryeong.semiontd.tower.gamble;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntUnaryOperator;

/** Three cards from a standard deck, without replacement. Card IDs are suit * 13 + rank - 2. */
public final class GamblePoker {
    public static final int MIN_BET = 200;
    public static final int MAX_BET = 1000;
    public static final String UPGRADE_ID = "poker_bet";

    private GamblePoker() {
    }

    public static boolean validBet(long amount) {
        return amount >= MIN_BET && amount <= MAX_BET;
    }

    public static Hand draw(IntUnaryOperator nextInt) {
        int[] deck = new int[52];
        Arrays.setAll(deck, i -> i);
        for (int i = 0; i < 3; i++) {
            int selected = i + nextInt.applyAsInt(52 - i);
            int swap = deck[i];
            deck[i] = deck[selected];
            deck[selected] = swap;
        }
        return evaluate(deck[0], deck[1], deck[2]);
    }

    public static Hand evaluate(int first, int second, int third) {
        int[] cards = {first, second, third};
        if (Arrays.stream(cards).anyMatch(card -> card < 0 || card >= 52)
                || first == second || first == third || second == third) {
            throw new IllegalArgumentException("A poker hand requires three distinct cards from a 52-card deck.");
        }
        int[] ranks = Arrays.stream(cards).map(card -> card % 13 + 2).sorted().toArray();
        boolean flush = first / 13 == second / 13 && first / 13 == third / 13;
        boolean straight = (ranks[1] == ranks[0] + 1 && ranks[2] == ranks[1] + 1)
                || (ranks[0] == 2 && ranks[1] == 3 && ranks[2] == 14);
        Kind kind;
        int score;
        if (flush && straight) {
            kind = Kind.STRAIGHT_FLUSH;
            score = 60;
        } else if (ranks[0] == ranks[2]) {
            kind = Kind.THREE_OF_A_KIND;
            score = 60;
        } else if (straight) {
            kind = Kind.STRAIGHT;
            score = 55;
        } else if (flush) {
            kind = Kind.FLUSH;
            score = 50;
        } else if (ranks[0] == ranks[1] || ranks[1] == ranks[2]) {
            kind = Kind.PAIR;
            score = 37 + ranks[1] - 2;
        } else {
            kind = Kind.HIGH_CARD;
            score = switch (ranks[2]) {
                case 9 -> 2;
                case 10 -> 4;
                case 11 -> 6;
                case 12 -> 27;
                case 13 -> 31;
                case 14 -> 35;
                default -> 0;
            };
        }
        return new Hand(List.of(first, second, third), kind, score);
    }

    public enum Kind {
        HIGH_CARD("하이 카드", 0), PAIR("원페어", 0), FLUSH("플러시", 1),
        STRAIGHT("스트레이트", 2), THREE_OF_A_KIND("트리플", 3), STRAIGHT_FLUSH("스트레이트 플러시", 3);

        private final String displayName;
        private final int debuffs;

        Kind(String displayName, int debuffs) {
            this.displayName = displayName;
            this.debuffs = debuffs;
        }
    }

    public record Hand(List<Integer> cards, Kind kind, int score) {
        public Hand {
            cards = List.copyOf(cards);
        }

        public boolean destroyed() {
            return score == 0;
        }

        public boolean weak() {
            return score > 0 && score <= 6;
        }

        public long weightedScore(long bet) {
            if (!validBet(bet)) {
                throw new IllegalArgumentException("Poker bet must be 200..1000 diamonds.");
            }
            return bet * score;
        }

        public int debuffCount(long bet, double threshold) {
            return weightedScore(bet) >= threshold ? kind.debuffs : 0;
        }

        public String displayName() {
            return kind.displayName;
        }

        public String cardsLabel() {
            return cards.stream().map(card -> {
                String suit = List.of("♠", "♥", "♦", "♣").get(card / 13);
                int rank = card % 13 + 2;
                return suit + switch (rank) {
                    case 11 -> "J";
                    case 12 -> "Q";
                    case 13 -> "K";
                    case 14 -> "A";
                    default -> Integer.toString(rank);
                };
            }).collect(java.util.stream.Collectors.joining(" "));
        }
    }
}
