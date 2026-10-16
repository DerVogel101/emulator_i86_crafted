package dervogel101.de.i86.runtime;

import java.nio.charset.Charset;

/** The emulator's eight 16-byte sign lines, independent of Minecraft text classes. */
public record SignLine(String text, int format) {
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    public static SignLine decode(byte[] sign, int side, int line) {
        if (sign.length != 128 || side < 0 || side > 1 || line < 0 || line > 3)
            throw new IllegalArgumentException("Invalid sign snapshot or line");
        int base = side * 64 + line * 16, length = 0;
        while (length < 15 && sign[base + 1 + length] != 0) length++;
        return new SignLine(new String(sign, base + 1, length, WINDOWS_1252), sign[base] & 255);
    }
}
