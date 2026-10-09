package sts1solver;

import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.map.MapEdge;
import com.megacrit.cardcrawl.map.MapRoomNode;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import javax.imageio.ImageIO;

/** Draws this act's map with the autopilot's planned route to a PNG, so a route decision can be shown and discussed. */
final class MapExport {
    private static final int CELL_X = 110, CELL_Y = 84, MARGIN = 70, TOP = 90;

    static Path write(Path directory, List<OutsidePacket.Step> route) throws Exception {
        List<? extends List<MapRoomNode>> map = AbstractDungeon.map;
        if (map == null || map.isEmpty()) throw new IllegalStateException("no map");
        int rows = map.size(), width = MARGIN * 2 + CELL_X * 6, height = TOP + CELL_Y * rows + MARGIN;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0xF4EFE3)); g.fillRect(0, 0, width, height);

        // Edges first: grey for the map, dark for the taken path.
        g.setStroke(new BasicStroke(2f));
        for (List<MapRoomNode> row : map) for (MapRoomNode node : row) {
            if (node.getEdges().isEmpty()) continue;
            for (MapEdge edge : node.getEdges()) {
                boolean walked = node.taken && edge.dstY < rows && map.get(edge.dstY).get(edge.dstX).taken;
                g.setColor(walked ? new Color(0x333333) : new Color(0xB8B0A0));
                g.setStroke(new BasicStroke(walked ? 4f : 2f));
                g.drawLine(px(node.x), py(node.y, rows), px(edge.dstX), py(edge.dstY, rows));
            }
        }
        // The planned route, numbered from the next step.
        MapRoomNode at = AbstractDungeon.getCurrMapNode();
        int lastX = at == null ? -1 : at.x, lastY = at == null ? -1 : at.y;
        g.setStroke(new BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0xE8833A));
        for (OutsidePacket.Step step : route) {
            if (lastX >= 0) g.drawLine(px(lastX), py(lastY, rows), px(step.x), py(step.y, rows));
            lastX = step.x; lastY = step.y;
        }
        // Nodes.
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        for (List<MapRoomNode> row : map) for (MapRoomNode node : row) {
            if (node.getEdges().isEmpty() && !hasParent(map, node)) continue;
            String symbol = node.getRoomSymbol(true);
            int cx = px(node.x), cy = py(node.y, rows);
            g.setColor(roomColor(symbol)); g.fillOval(cx - 19, cy - 19, 38, 38);
            g.setColor(node.taken ? new Color(0x333333) : Color.WHITE);
            g.setStroke(new BasicStroke(node.taken ? 4f : 2f));
            g.drawOval(cx - 19, cy - 19, 38, 38);
            if (node.hasEmeraldKey) { g.setColor(new Color(0x1B9E5A)); g.setStroke(new BasicStroke(4f)); g.drawOval(cx - 24, cy - 24, 48, 48); }
            g.setColor(Color.WHITE);
            FontMetrics m = g.getFontMetrics();
            g.drawString(symbol, cx - m.stringWidth(symbol) / 2, cy + m.getAscent() / 2 - 2);
        }
        if (at != null) {
            g.setColor(new Color(0x2060D0)); g.setStroke(new BasicStroke(5f));
            g.drawOval(px(at.x) - 26, py(at.y, rows) - 26, 52, 52);
        }
        int n = 1;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        for (OutsidePacket.Step step : route) {
            int cx = px(step.x) + 18, cy = py(step.y, rows) - 18;
            g.setColor(new Color(0xE8833A)); g.fillOval(cx - 11, cy - 11, 22, 22);
            g.setColor(Color.WHITE);
            String label = Integer.toString(n++);
            g.drawString(label, cx - g.getFontMetrics().stringWidth(label) / 2, cy + 5);
        }
        g.setColor(new Color(0x222222)); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        String title = "Act " + AbstractDungeon.actNum + "  Floor " + AbstractDungeon.floorNum + "  HP "
            + AbstractDungeon.player.currentHealth + "/" + AbstractDungeon.player.maxHealth + "  Gold " + AbstractDungeon.player.gold;
        g.drawString(title, MARGIN, 34);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        g.drawString("orange = AI planned route (numbered)   blue ring = you   green ring = burning elite   black = taken   seed "
            + Settings.seed, MARGIN, 62);
        g.dispose();

        Files.createDirectories(directory);
        Path file = directory.resolve("sts1-map-A" + AbstractDungeon.actNum + "-F" + AbstractDungeon.floorNum + "-"
            + new SimpleDateFormat("MMdd-HHmmss").format(new Date()) + ".png");
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private static boolean hasParent(List<? extends List<MapRoomNode>> map, MapRoomNode target) {
        if (target.y == 0) return false;
        for (MapRoomNode node : map.get(target.y - 1))
            for (MapEdge edge : node.getEdges()) if (edge.dstX == target.x && edge.dstY == target.y) return true;
        return false;
    }

    private static int px(int x) { return MARGIN + x * CELL_X; }
    private static int py(int y, int rows) { return TOP + (rows - 1 - y) * CELL_Y; }

    private static Color roomColor(String symbol) {
        switch (symbol) {
            case "E": return new Color(0xC0392B);
            case "R": return new Color(0xD68910);
            case "$": return new Color(0x8E6B1F);
            case "T": return new Color(0x7D5A3C);
            case "?": return new Color(0x5B7DB1);
            default: return new Color(0x6E6E6E);
        }
    }
}
