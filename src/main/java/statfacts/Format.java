package statfacts;

/** Number formatting, matching the datapack's fmt/int and fmt/dec functions. */
final class Format {
    private Format() {}

    /** Thousands separators: 1234567 -> "1,234,567". */
    static String groups(long value) {
        String digits = Long.toString(Math.abs(value));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                out.append(',');
            }
            out.append(digits.charAt(i));
        }
        return (value < 0 ? "-" : "") + out;
    }

    /** Input is in tenths: 15 -> "1.5", 1234 -> "123.4", 5 -> "0.5". */
    static String tenths(long tenths) {
        long whole = tenths / 10;
        long decimal = Math.abs(tenths % 10);
        return groups(whole) + "." + decimal;
    }
}
