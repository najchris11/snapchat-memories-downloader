import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import javax.imageio.ImageIO;

/** Creates deliberately abstract artwork only; it never reads personal files or media. */
public final class DemoLibraryGenerator {
    private static final String[][] MEMORIES = {
        {"2025-08-16_summer-signal.png", "SUMMER SIGNAL", "#8257E6", "#F4B7FF", "true", "false"},
        {"2025-07-04_midnight-postcard.png", "MIDNIGHT POSTCARD", "#171D4D", "#4C8DFF", "false", "true"},
        {"2025-05-29_orbit-study.png", "ORBIT STUDY", "#0E4D53", "#78E1D5", "true", "true"},
        {"2025-04-12_paper-sun.png", "PAPER SUN", "#D95D39", "#FFD166", "false", "false"},
        {"2024-12-31_new-year-glow.png", "NEW YEAR GLOW", "#28104E", "#F5D0FE", "true", "true"},
        {"2024-10-03_copper-sky.png", "COPPER SKY", "#6F1D1B", "#F4A261", "false", "false"},
        {"2024-06-21_solstice-lines.png", "SOLSTICE LINES", "#005F73", "#94D2BD", "true", "false"},
        {"2023-09-14_after-rain.png", "AFTER RAIN", "#243B53", "#9FB3C8", "false", "true"}
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected destination directory");
        System.setProperty("java.awt.headless", "true");
        Path destination = new File(args[0]).toPath();
        for (int i = 0; i < MEMORIES.length; i++) {
            String[] memory = MEMORIES[i];
            File output = destination.resolve(memory[0]).toFile();
            render(output, memory[1], Color.decode(memory[2]), Color.decode(memory[3]), i);
            // Deliberately make every filesystem timestamp unrelated to capture date; this is a
            // visible regression fixture for Library's filename-date behavior.
            Files.setLastModifiedTime(output.toPath(), FileTime.from(Instant.parse("2026-09-01T12:00:00Z")));
        }
        writeIndex(destination);
    }

    private static void render(File output, String title, Color start, Color end, int index) throws Exception {
        int width = 1280, height = 960;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, start, width, height, end));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(255, 255, 255, 38));
        for (int i = 0; i < 6; i++) {
            int size = 160 + (i * 90);
            int x = (index * 137 + i * 211) % width - size / 3;
            int y = (index * 83 + i * 151) % height - size / 3;
            g.fill(new Ellipse2D.Double(x, y, size, size));
        }
        g.setColor(new Color(255, 255, 255, 110));
        g.setStroke(new BasicStroke(10f));
        for (int i = 0; i < 5; i++) g.drawArc(120 + i * 115, 125 + i * 65, 720, 520, 18 + i * 21, 205);
        g.setColor(new Color(255, 255, 255, 220));
        g.setFont(new Font("SansSerif", Font.BOLD, 58));
        g.drawString(title, 72, 830);
        g.setFont(new Font("SansSerif", Font.PLAIN, 24));
        g.drawString("SNAPVAULT · SYNTHETIC DEMO", 76, 875);
        g.dispose();
        if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG writer unavailable");
    }

    private static void writeIndex(Path destination) throws Exception {
        try (FileWriter out = new FileWriter(destination.resolve("vault_index.json").toFile())) {
            out.write("{\n");
            for (int i = 0; i < MEMORIES.length; i++) {
                String[] memory = MEMORIES[i];
                out.write("  \"" + memory[0] + "\": {\"hasGps\": " + memory[4] + ", \"hasOverlay\": " + memory[5] + "}");
                out.write(i + 1 == MEMORIES.length ? "\n" : ",\n");
            }
            out.write("}\n");
        }
    }
}
