package kim.biryeong.semiontd.ui.rp;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class GambleGlyphsTest {
    @Test
    void fontMapsEveryCardBackDieAndSlotToAVisibleAtlasCell() throws Exception {
        var characters = new HashSet<Integer>();
        for (var element : GambleGlyphs.fontDefinition().getAsJsonArray("providers")) {
            var provider = element.getAsJsonObject();
            String file = provider.get("file").getAsString();
            String name = file.substring(file.lastIndexOf('/') + 1, file.length() - 4);
            BufferedImage atlas = GambleGlyphs.atlas(name);
            var rows = provider.getAsJsonArray("chars");
            int columns = rows.get(0).getAsString().length();
            assertEquals(0, atlas.getWidth() % columns);
            assertEquals(0, atlas.getHeight() % rows.size());
            int cellWidth = atlas.getWidth() / columns;
            int cellHeight = atlas.getHeight() / rows.size();
            for (int row = 0; row < rows.size(); row++) {
                String chars = rows.get(row).getAsString();
                assertEquals(columns, chars.length());
                for (int col = 0; col < columns; col++) {
                    assertTrue(characters.add((int) chars.charAt(col)), "Each glyph needs a unique codepoint");
                    assertNotEquals(0, atlas.getRGB(col * cellWidth + cellWidth / 2,
                            row * cellHeight + cellHeight / 2) >>> 24, "The glyph cell must be visible");
                }
            }
        }
        assertEquals(65, characters.size());
        Path reports = Path.of("build/reports/gamble-glyphs");
        Files.createDirectories(reports);
        BufferedImage preview = new BufferedImage(624, 440, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = preview.createGraphics();
        try {
            g.setColor(new Color(0x172138)); g.fillRect(0, 0, 624, 440);
            g.drawImage(GambleGlyphs.atlas("cards"), 40, 20, null);
            g.drawImage(GambleGlyphs.atlas("card_back"), 40, 282, 60, 84, null);
            g.drawImage(GambleGlyphs.atlas("dice"), 120, 280, 288, 48, null);
            g.drawImage(GambleGlyphs.atlas("slots"), 120, 344, 384, 64, null);
        } finally { g.dispose(); }
        ImageIO.write(preview, "png", reports.resolve("preview.png").toFile());
    }
}
