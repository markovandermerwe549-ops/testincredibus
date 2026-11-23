import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Draws the launcher icon: a terminal prompt on a rounded teal tile. */
public class MakeIcon {
    public static void main(String[] a) throws Exception {
        int s = 192;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x10, 0x14, 0x18));
        g.fillRoundRect(8, 8, s - 16, s - 16, 44, 44);
        g.setColor(new Color(0x2B, 0xD4, 0xC4));
        g.setStroke(new BasicStroke(10f));
        g.drawRoundRect(13, 13, s - 26, s - 26, 40, 40);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 70));
        g.drawString(">_", 36, 118);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        g.setColor(Color.WHITE);
        g.drawString("ADB", 66, 162);
        g.dispose();
        ImageIO.write(img, "png", new File(a[0]));
    }
}
