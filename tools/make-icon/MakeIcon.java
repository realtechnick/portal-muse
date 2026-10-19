import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Makes the launcher icons from the character's idle frame 0: the frame cut to the character, scaled
 * up by a whole number with no smoothing, on the dark ground the app uses.
 *
 * <pre>java tools/make-icon/MakeIcon.java android/app/src/main/assets/muse-avatar/idle.png android/app/src/main/res</pre>
 */
public class MakeIcon {
  static final int CELL = 64;
  static final Color GROUND = new Color(0x24, 0x31, 0x42);
  static final double GRID = 0.72;
  static final String[] DENSITIES = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
  static final double[] SCALES = {1, 1.5, 2, 3, 4};

  public static void main(String[] args) throws Exception {
    BufferedImage sheet = ImageIO.read(new File(args[0]));
    BufferedImage frame = trim(sheet.getSubimage(0, 0, CELL, CELL));
    File res = new File(args[1]);
    for (int i = 0; i < DENSITIES.length; i++) {
      int legacy = (int) Math.round(48 * SCALES[i]);
      int adaptive = (int) Math.round(108 * SCALES[i]);
      File dir = new File(res, "mipmap-" + DENSITIES[i]);
      dir.mkdirs();
      // A legacy icon draws its own rounded tile; the character fills about three quarters of it.
      write(icon(frame, legacy, legacy * 0.78, true), new File(dir, "ic_launcher.png"));
      // An adaptive foreground keeps the character near the 66 dp the launcher's mask never cuts; the character is round, so a little more still clears a circle.
      write(icon(frame, adaptive, adaptive * 70 / 108.0, false), new File(dir, "ic_launcher_foreground.png"));
    }
  }

  /**
   * The frame cut to the character alone: the largest connected patch of solid pixels, without the
   * sparkles and glow around it, which would leave the character small in a small icon.
   */
  static BufferedImage trim(BufferedImage f) {
    int n = CELL * CELL;
    boolean[] solid = new boolean[n];
    for (int i = 0; i < n; i++) solid[i] = (f.getRGB(i % CELL, i / CELL) >>> 24) > 0xC0;
    int[] label = new int[n];
    int best = 0, bestSize = 0, next = 0;
    int[] stack = new int[n];
    for (int i = 0; i < n; i++) {
      if (!solid[i] || label[i] != 0) continue;
      int id = ++next, size = 0, top = 0;
      stack[top++] = i;
      label[i] = id;
      while (top > 0) {
        int p = stack[--top];
        size++;
        int px = p % CELL, py = p / CELL;
        int[][] around = {{px - 1, py}, {px + 1, py}, {px, py - 1}, {px, py + 1}};
        for (int[] q : around) {
          if (q[0] < 0 || q[1] < 0 || q[0] >= CELL || q[1] >= CELL) continue;
          int j = q[1] * CELL + q[0];
          if (solid[j] && label[j] == 0) {
            label[j] = id;
            stack[top++] = j;
          }
        }
      }
      if (size > bestSize) {
        bestSize = size;
        best = id;
      }
    }
    int x0 = CELL, y0 = CELL, x1 = -1, y1 = -1;
    for (int i = 0; i < n; i++)
      if (label[i] == best) {
        x0 = Math.min(x0, i % CELL);
        y0 = Math.min(y0, i / CELL);
        x1 = Math.max(x1, i % CELL);
        y1 = Math.max(y1, i / CELL);
      }
    BufferedImage out = new BufferedImage(x1 - x0 + 1, y1 - y0 + 1, BufferedImage.TYPE_INT_ARGB);
    for (int y = y0; y <= y1; y++)
      for (int x = x0; x <= x1; x++)
        if (label[y * CELL + x] == best) out.setRGB(x - x0, y - y0, f.getRGB(x, y));
    return out;
  }

  static BufferedImage icon(BufferedImage c, int size, double box, boolean tile) {
    BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = out.createGraphics();
    if (tile) {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(GROUND);
      g.fill(new RoundRectangle2D.Double(0, 0, size, size, size * 0.44, size * 0.44));
    }
    int longest = Math.max(c.getWidth(), c.getHeight());
    int scale = (int) Math.floor(box / longest);
    if (scale >= 1) {
      int w = c.getWidth() * scale, h = c.getHeight() * scale;
      int ox = (size - w) / 2, oy = (size - h) / 2;
      for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
          int argb = c.getRGB(x / scale, y / scale);
          int a = argb >>> 24;
          if (a == 0) continue;
          boolean edge = scale >= 3 && (x % scale == scale - 1 || y % scale == scale - 1);
          if (edge) argb = shade(argb);
          out.setRGB(ox + x, oy + y, blend(out.getRGB(ox + x, oy + y), argb));
        }
    } else {
      // Smaller than the character's own pixels: averaged down, the only way it fits.
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      int w = (int) Math.round(c.getWidth() * box / longest), h = (int) Math.round(c.getHeight() * box / longest);
      g.drawImage(c, (size - w) / 2, (size - h) / 2, w, h, null);
    }
    g.dispose();
    return out;
  }

  static int shade(int argb) {
    int r = (int) (((argb >> 16) & 0xff) * GRID), gg = (int) (((argb >> 8) & 0xff) * GRID), b = (int) ((argb & 0xff) * GRID);
    return (argb & 0xff000000) | (r << 16) | (gg << 8) | b;
  }

  static int blend(int under, int over) {
    double a = (over >>> 24) / 255.0, ua = (under >>> 24) / 255.0;
    double oa = a + ua * (1 - a);
    if (oa == 0) return 0;
    int[] o = new int[3];
    for (int k = 0; k < 3; k++) {
      int shift = 16 - 8 * k;
      double cu = (under >> shift) & 0xff, co = (over >> shift) & 0xff;
      o[k] = (int) Math.round((co * a + cu * ua * (1 - a)) / oa);
    }
    return ((int) Math.round(oa * 255) << 24) | (o[0] << 16) | (o[1] << 8) | o[2];
  }

  static void write(BufferedImage img, File f) throws Exception {
    ImageIO.write(img, "png", f);
    System.out.println(f + " " + img.getWidth() + "px");
  }
}
