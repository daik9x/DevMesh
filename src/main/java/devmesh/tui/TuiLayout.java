package devmesh.tui;

import java.util.regex.Pattern;

/** Small pure layout helpers shared by terminal renderers. */
public final class TuiLayout {
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*m");

    private TuiLayout() {}

    public static String clip(String value, int maxColumns) {
        if (value == null || maxColumns <= 0) return "";
        String plain = ANSI.matcher(value).replaceAll("");
        if (plain.codePointCount(0, plain.length()) <= maxColumns) return value;
        int end = plain.offsetByCodePoints(0, Math.max(0, maxColumns - 1));
        return plain.substring(0, end) + "…";
    }

    public static String statusLine(String left, String right, int width) {
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        int columns = Math.max(20, width);
        int leftWidth = visibleWidth(safeLeft);
        int rightWidth = visibleWidth(safeRight);
        if (rightWidth == 0 || leftWidth + rightWidth + 2 >= columns) return clip(safeLeft, columns);
        return safeLeft + " ".repeat(Math.max(1, columns - leftWidth - rightWidth)) + safeRight;
    }

    private static int visibleWidth(String value) {
        return ANSI.matcher(value).replaceAll("").codePointCount(0, ANSI.matcher(value).replaceAll("").length());
    }
}
