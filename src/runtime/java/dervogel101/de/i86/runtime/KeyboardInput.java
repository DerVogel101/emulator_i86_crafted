package dervogel101.de.i86.runtime;

import java.nio.charset.Charset;

/** Stateless SDL physical-key maps: dead accents never enter an OS composition buffer. */
public final class KeyboardInput {
    public enum Layout { US, DE }
    private static final Charset ENCODING = Charset.forName("windows-1258");
    private static final boolean[] ALLOWED = allowed();
    private KeyboardInput() {}

    public static int translate(Layout layout, int scancode, int modifiers) {
        int key;
        if (scancode >= 4 && scancode <= 29) key = 65 + scancode - 4;
        else if (scancode >= 30 && scancode <= 38) key = 49 + scancode - 30;
        else if (scancode >= 89 && scancode <= 97) key = 321 + scancode - 89;
        else if (scancode >= 84 && scancode <= 88) key = 331 + scancode - 84;
        else key = switch (scancode) {
            case 39 -> 48; case 40 -> 257; case 42 -> 259; case 43 -> 258; case 44 -> 32;
            case 45 -> 45; case 46 -> 61; case 47 -> 91; case 48 -> 93; case 49, 50 -> 92;
            case 51 -> 59; case 52 -> 39; case 53 -> 96; case 54 -> 44; case 55 -> 46; case 56 -> 47;
            case 73 -> 260; case 74 -> 268; case 75 -> 266; case 76 -> 261; case 77 -> 269; case 78 -> 267;
            case 79 -> 262; case 80 -> 263; case 81 -> 264; case 82 -> 265;
            case 98 -> 320; case 99 -> 330; case 100 -> 162; case 103 -> 336; default -> -1;
        };
        int normalized = ((modifiers & 3) != 0 ? 1 : 0) | ((modifiers & 0xc0) != 0 ? 2 : 0)
                | ((modifiers & 0x300) != 0 ? 4 : 0) | ((modifiers & 0xc00) != 0 ? 8 : 0)
                | ((modifiers & 0x2000) != 0 ? 16 : 0);
        return character(layout, key, normalized, (modifiers & 0x200) != 0);
    }
    private static int character(Layout layout, int key, int modifiers, boolean rightAlt) {
        boolean shift = (modifiers & 1) != 0, caps = (modifiers & 16) != 0;
        int special = special(key);
        if (special >= 0) return (modifiers & 15) == 0 ? special : -1;
        boolean altGr = layout == Layout.DE && rightAlt && (modifiers & 4) != 0;
        if ((modifiers & 8) != 0 || !altGr && (modifiers & 6) != 0) return -1;
        char character;
        if (altGr) character = switch (key) {
            case 81 -> '@'; case 69 -> '\u20ac'; case 77 -> '\u00b5';
            case 50 -> '\u00b2'; case 51 -> '\u00b3';
            case 55 -> '{'; case 56 -> '['; case 57 -> ']'; case 48 -> '}';
            case 45 -> '\\'; case 93 -> '~'; case 161, 162 -> '|'; default -> 0;
        };
        else if (key >= 65 && key <= 90) {
            int letter = layout == Layout.DE && key == 89 ? 90 : layout == Layout.DE && key == 90 ? 89 : key;
            character = (char) (shift ^ caps ? letter : letter + 32);
        } else if (key >= 48 && key <= 57) {
            character = shift ? (layout == Layout.DE ? "=!\"\u00a7$%&/()" : ")!@#$%^&*(").charAt(key - 48) : (char) key;
        } else if (layout == Layout.DE) {
            character = switch (key) {
                case 32 -> ' '; case 45 -> shift ? '?' : '\u00df'; case 61 -> shift ? '`' : '\u00b4';
                case 91 -> shift ^ caps ? '\u00dc' : '\u00fc'; case 93 -> shift ? '*' : '+';
                case 59 -> shift ^ caps ? '\u00d6' : '\u00f6'; case 39 -> shift ^ caps ? '\u00c4' : '\u00e4';
                case 92 -> shift ? '\'' : '#'; case 96 -> shift ? '\u00b0' : '^';
                case 44 -> shift ? ';' : ','; case 46 -> shift ? ':' : '.'; case 47 -> shift ? '_' : '-';
                case 161, 162 -> shift ? '>' : '<'; default -> 0;
            };
        } else character = switch (key) {
            case 32 -> ' '; case 45 -> shift ? '_' : '-'; case 61 -> shift ? '+' : '=';
            case 91 -> shift ? '{' : '['; case 93 -> shift ? '}' : ']'; case 59 -> shift ? ':' : ';';
            case 39 -> shift ? '"' : '\''; case 92 -> shift ? '|' : '\\'; case 96 -> shift ? '~' : '`';
            case 44 -> shift ? '<' : ','; case 46 -> shift ? '>' : '.'; case 47 -> shift ? '?' : '/'; default -> 0;
        };
        if (character == 0 || !ENCODING.newEncoder().canEncode(character)) return -1;
        return Byte.toUnsignedInt(Character.toString(character).getBytes(ENCODING)[0]);
    }
    private static int special(int key) {
        if (key >= 320 && key <= 336) return 0x81 + key - 320;
        return switch (key) {
            case 257 -> 10; case 258 -> 9; case 259 -> 8;
            case 263 -> 0x92; case 262 -> 0x93; case 265 -> 0x94; case 264 -> 0x95;
            case 260 -> 0x96; case 261 -> 0x97; case 268 -> 0x98; case 269 -> 0x99;
            case 266 -> 0x9a; case 267 -> 0x9b; default -> -1;
        };
    }
    public static boolean allowedByte(int value) { return value > 0 && value < 256 && ALLOWED[value]; }
    private static boolean[] allowed() {
        var result = new boolean[256];
        for (var layout : Layout.values()) for (int key = 32; key <= 348; key++)
            for (int modifiers : new int[]{0, 1, 16, 17, 6, 7}) {
                int value = character(layout, key, modifiers, (modifiers & 4) != 0);
                if (value >= 0) result[value] = true;
            }
        return result;
    }
}
