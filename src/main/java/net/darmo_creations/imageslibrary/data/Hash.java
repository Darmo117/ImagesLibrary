package net.darmo_creations.imageslibrary.data;

import org.jetbrains.annotations.*;

import javax.imageio.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.nio.file.*;

/**
 * This class represents the 64-bit dHash of an image.
 * See <a href="http://www.hackerfactor.com/blog/index.php?/archives/529-Kind-of-Like-That.html">this blog post</a>
 * for more information.
 *
 * @param bytes The hash’s bytes.
 */
public record Hash(long bytes) {
  /**
   * The Hamming distance value at or under which two hashes are considered to be similar.
   */
  public static final int SIM_DIST_THRESHOLD = 10;

  /**
   * Compares this hash with the given one. Two hashes are considered similar if their Hamming distance
   * is ≤ 10.
   * See <a href="http://www.hackerfactor.com/blog/index.php?/archives/529-Kind-of-Like-That.html">this blog post</a>
   * for more information.
   * <p>
   * The similarity confidence index is computed using the formula (11 - d - 0.1) / 11 if the distance
   * is ≤ 10; it is 0 otherwise. Explanation of the formula:
   * <ul>
   * <li>0.1 is subtracted from the distance {@code d} because even if it is 0,
   * we cannot be 100% sure that the two images behind the hashes are strictly identical.</li>
   * <li>11 is used instead of 10 so that if the distance is 10, the result remains positive.</li>
   * </ul>
   *
   * @param other A hash to compare to this one.
   * @return A {@link Similarity} object that contains the computed Hamming distance and confidence index.
   */
  @Contract(pure = true, value = "_ -> new")
  public Similarity computeSimilarity(Hash other) {
    final long dist = this.hammingDistance(other);
    final float confidence;
    if (dist <= SIM_DIST_THRESHOLD)
      confidence = ((SIM_DIST_THRESHOLD + 1) - dist - 0.1f) / (SIM_DIST_THRESHOLD + 1);
    else
      confidence = 0;
    return new Similarity(dist, confidence);
  }

  /**
   * Compute the Hamming distance between this hash and the given one.
   *
   * @param other Another hash.
   * @return The Hamming distance.
   */
  @Contract(pure = true)
  private int hammingDistance(Hash other) {
    final String thisBin = to64BitsString(this);
    final String thatBin = to64BitsString(other);
    int distCounter = 0;
    for (int i = 0; i < 64; i++) {
      if (thisBin.charAt(i) != thatBin.charAt(i))
        distCounter++;
    }
    return distCounter;
  }

  /**
   * Convert a hash into a 64-bit binary string.
   *
   * @param hash The hash to convert.
   * @return The 64-bit binary string representation of the hash.
   */
  @Contract(pure = true, value = "_ -> new")
  private static String to64BitsString(Hash hash) {
    return "%64s".formatted(Long.toBinaryString(hash.bytes())).replace(' ', '0');
  }

  /**
   * Compute the dHash for the given image file.
   * See <a href="http://www.hackerfactor.com/blog/index.php?/archives/529-Kind-of-Like-That.html">this blog post</a>
   * for more information.
   *
   * @param file The image file to compute the hash of.
   * @return The computed hash.
   * @throws IOException If any file error occurs.
   */
  @Contract(pure = true, value = "_ -> new")
  public static Hash computeForFile(final Path file) throws IOException {
    @Nullable
    final var image = ImageIO.read(file.toFile());
    if (image == null)
      throw new IOException("Could not read file at %s".formatted(file));
    return new Hash(computeDifferenceHash(toGrayscale(resizeTo9By8(image))));
  }

  /**
   * Convert an image to grayscale.
   * <p>
   * Part of the code is from: https://memorynotfound.com/convert-image-grayscale-java/
   *
   * @param image The image to convert.
   * @return A new grayscale image version of the given image.
   */
  @Contract(pure = true, value = "_ -> new")
  private static BufferedImage toGrayscale(final BufferedImage image) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    final var grayscale = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
    for (int x = 0; x < width; x++) {
      for (int y = 0; y < height; y++) {
        final Color rgb = new Color(image.getRGB(x, y));
        final int gray = (int) (rgb.getRed() * 0.299 + rgb.getGreen() * 0.587 + rgb.getBlue() * 0.114);
        grayscale.setRGB(x, y, new Color(gray, gray, gray).getRGB());
      }
    }
    return grayscale;
  }

  /**
   * Resize the given image to 9×8 pixels.
   *
   * @param image The image to resize.
   * @return A new image.
   */
  @Contract(pure = true, value = "_ -> new")
  private static BufferedImage resizeTo9By8(final BufferedImage image) {
    final int w = 9, h = 8;
    final var resizedImage = new BufferedImage(w, h, image.getType());
    resizedImage.getGraphics().drawImage(image.getScaledInstance(w, h, Image.SCALE_DEFAULT), 0, 0, null);
    return resizedImage;
  }

  /**
   * Compute the difference hash for the given image.
   *
   * @param image A 9×8 grayscale image.
   * @return The dHash for the image.
   */
  @Contract(pure = true)
  private static long computeDifferenceHash(final BufferedImage image) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    if (width != 9 || height != 8)
      throw new IllegalArgumentException("Expected image of size 9x8, got %dx%d".formatted(width, height));
    final int type = image.getType();
    if (type != BufferedImage.TYPE_BYTE_GRAY)
      throw new IllegalArgumentException("Expected a grayscale image (%d), got %d".formatted(BufferedImage.TYPE_BYTE_GRAY, type));

    // Build the hash by concatenating, for each line, 1 when pixel[x] < pixel[x + 1], 0 otherwise
    long hash = 0;
    for (int y = 0, p = 0; y < height; y++) {
      for (int x = 0; x < width - 1; x++, p++) {
        if (image.getRGB(x, y) < image.getRGB(x + 1, y))
          hash |= 1L << p;
      }
    }
    return hash;
  }
}
