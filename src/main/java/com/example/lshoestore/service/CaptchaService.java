package com.example.lshoestore.service;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.QuadCurve2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;

@Service
public class CaptchaService {
    private static final String SESSION_HASH = "REGISTER_CAPTCHA_HASH";
    private static final String SESSION_SALT = "REGISTER_CAPTCHA_SALT";
    private static final String SESSION_CREATED_AT = "REGISTER_CAPTCHA_CREATED_AT";

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final int IMAGE_WIDTH = 220;
    private static final int IMAGE_HEIGHT = 72;
    private static final Duration TTL = Duration.ofMinutes(2);

    private final SecureRandom random = new SecureRandom();

    public byte[] createImage(HttpSession session) {
        String code = randomCode();
        byte[] salt = new byte[16];
        random.nextBytes(salt);

        session.setAttribute(SESSION_SALT, salt);
        session.setAttribute(SESSION_HASH, hash(salt, code));
        session.setAttribute(SESSION_CREATED_AT, System.currentTimeMillis());

        return render(code);
    }

    public boolean verify(String answer, HttpSession session) {
        Object storedHash = session.getAttribute(SESSION_HASH);
        Object storedSalt = session.getAttribute(SESSION_SALT);
        Object createdAt = session.getAttribute(SESSION_CREATED_AT);

        // A challenge is single-use, regardless of success or failure.
        clear(session);

        if (!(storedHash instanceof byte[] expectedHash)
                || !(storedSalt instanceof byte[] salt)
                || !(createdAt instanceof Long issuedAt)
                || answer == null) {
            return false;
        }

        long age = System.currentTimeMillis() - issuedAt;
        if (age < 0 || age > TTL.toMillis()) {
            return false;
        }

        String normalized = answer.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() != CODE_LENGTH) {
            return false;
        }

        return MessageDigest.isEqual(expectedHash, hash(salt, normalized));
    }

    private void clear(HttpSession session) {
        session.removeAttribute(SESSION_HASH);
        session.removeAttribute(SESSION_SALT);
        session.removeAttribute(SESSION_CREATED_AT);
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    private byte[] hash(byte[] salt, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(code.getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private byte[] render(String code) {
        BufferedImage source = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = source.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(new Color(247, 249, 252));
            graphics.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

            drawNoiseDots(graphics);
            drawInterferenceCurves(graphics);
            drawCharacters(graphics, code);
        } finally {
            graphics.dispose();
        }

        BufferedImage warped = waveWarp(source);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(warped, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not render CAPTCHA image", exception);
        }
    }

    private void drawCharacters(Graphics2D graphics, String code) {
        Font[] fonts = {
                new Font(Font.SANS_SERIF, Font.BOLD, 38),
                new Font(Font.SERIF, Font.BOLD, 39),
                new Font(Font.MONOSPACED, Font.BOLD, 37)
        };
        Color[] colors = {
                new Color(31, 41, 55),
                new Color(49, 46, 129),
                new Color(15, 82, 96),
                new Color(88, 28, 135)
        };

        int startX = 18;
        int step = 31;
        for (int i = 0; i < code.length(); i++) {
            String character = String.valueOf(code.charAt(i));
            int x = startX + i * step + random.nextInt(-2, 4);
            int y = 49 + random.nextInt(-6, 7);
            double angle = Math.toRadians(random.nextInt(-19, 20));

            AffineTransform previous = graphics.getTransform();
            graphics.rotate(angle, x + 12, y - 15);
            graphics.setFont(fonts[random.nextInt(fonts.length)]);
            graphics.setColor(colors[random.nextInt(colors.length)]);
            graphics.drawString(character, x, y);
            graphics.setTransform(previous);
        }
    }

    private void drawNoiseDots(Graphics2D graphics) {
        for (int i = 0; i < 260; i++) {
            int shade = random.nextInt(155, 226);
            graphics.setColor(new Color(shade, shade, shade));
            int size = random.nextInt(1, 3);
            graphics.fillOval(random.nextInt(IMAGE_WIDTH), random.nextInt(IMAGE_HEIGHT), size, size);
        }
    }

    private void drawInterferenceCurves(Graphics2D graphics) {
        graphics.setStroke(new BasicStroke(1.3f));
        for (int i = 0; i < 6; i++) {
            int shade = random.nextInt(115, 190);
            graphics.setColor(new Color(shade, shade, shade, 190));
            int y1 = random.nextInt(8, IMAGE_HEIGHT - 8);
            int controlY = random.nextInt(4, IMAGE_HEIGHT - 4);
            int y2 = random.nextInt(8, IMAGE_HEIGHT - 8);
            graphics.draw(new QuadCurve2D.Double(
                    -5, y1,
                    IMAGE_WIDTH / 2.0 + random.nextInt(-25, 26), controlY,
                    IMAGE_WIDTH + 5, y2));
        }
    }

    private BufferedImage waveWarp(BufferedImage source) {
        BufferedImage target = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < IMAGE_HEIGHT; y++) {
            for (int x = 0; x < IMAGE_WIDTH; x++) {
                int sourceX = x + (int) Math.round(2.2 * Math.sin((y + 7) / 9.0));
                int sourceY = y + (int) Math.round(1.8 * Math.sin((x + 11) / 13.0));
                sourceX = Math.max(0, Math.min(IMAGE_WIDTH - 1, sourceX));
                sourceY = Math.max(0, Math.min(IMAGE_HEIGHT - 1, sourceY));
                target.setRGB(x, y, source.getRGB(sourceX, sourceY));
            }
        }
        return target;
    }
}
