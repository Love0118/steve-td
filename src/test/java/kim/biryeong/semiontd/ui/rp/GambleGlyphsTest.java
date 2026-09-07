package kim.biryeong.semiontd.ui.rp;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class GambleGlyphsTest {
    @Test
    void fontMapsEveryCardBackDieAndSlotToAVisibleAtlasCell() {
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
    }
}
