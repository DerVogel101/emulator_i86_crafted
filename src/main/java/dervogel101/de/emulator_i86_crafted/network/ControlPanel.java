package dervogel101.de.emulator_i86_crafted.network;

import java.util.*;

/** Data for our client screens; server actions remain bound to a one-use session. */
public record ControlPanel(Kind kind, UUID token, String icon, String title, String body, List<Field> fields, List<Action> actions) {
    public enum Kind { MACHINE, PORT, BURNER }
    public ControlPanel {
        Objects.requireNonNull(kind); Objects.requireNonNull(token);
        if (!icon.matches("[a-z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid panel icon");
        bounded(title, 128); bounded(body, 24576);
        fields = List.copyOf(fields); actions = List.copyOf(actions);
        if (fields.size() > 16 || actions.size() > 32) throw new IllegalArgumentException("Too many panel controls");
        if (fields.stream().map(Field::key).distinct().count() != fields.size()) throw new IllegalArgumentException("Duplicate panel field");
    }
    public record Field(String key, String label, String value, int maximum, List<String> options) {
        public Field {
            ControlPanel.key(key); bounded(label, 128);
            if (maximum < 1 || maximum > 4096) throw new IllegalArgumentException("Invalid field limit");
            bounded(value, maximum); options = List.copyOf(options);
            if (options.size() > 128) throw new IllegalArgumentException("Too many options");
            for (var option : options) bounded(option, maximum);
            if (!options.isEmpty() && !options.contains(value)) throw new IllegalArgumentException("Invalid initial option");
        }
    }
    public record Action(String label, String action, Map<String, String> values) {
        public Action { bounded(label, 256); bounded(action, 32); values = ControlPanel.values(values); }
    }
    public static Field text(String key, String label, String value, int maximum) { return new Field(key, label, value, maximum, List.of()); }
    public static Field option(String key, String label, String value, List<String> options) { return new Field(key, label, value, 128, options); }
    public static void bounded(String value, int maximum) {
        if (value == null || value.length() > maximum) throw new IllegalArgumentException("Panel text exceeds limit");
    }
    public static void key(String key) {
        if (!key.matches("[a-z_]{1,32}") || key.equals("token") || key.equals("action")) throw new IllegalArgumentException("Invalid panel field key");
    }
    public static Map<String, String> values(Map<String, String> values) {
        if (values.size() > 16) throw new IllegalArgumentException("Too many panel values");
        values.forEach((key, value) -> { key(key); bounded(value, 4096); });
        return Map.copyOf(values);
    }
}
