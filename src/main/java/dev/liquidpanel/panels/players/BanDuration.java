package dev.liquidpanel.panels.players;

/**
 * 一段封禁时长。
 *
 * <p>面板上是「年 / 月 / 周 / 天 / 时 / 分 / 秒」七个输入框，
 * 这里把它们换算成一个毫秒数，同时保留原始的分段值 ——
 * 因为 LiteBans / AdvancedBan 那类插件要的是 {@code 1mo2d3h} 这种带单位的字符串，
 * 而不是毫秒，换算成毫秒之后再拆回去会丢信息（比如 30 天算不算 1 个月）。
 *
 * <p>换算固定为：1 年 = 365 天，1 月 = 30 天，1 周 = 7 天。
 * 这是封禁类插件的通行做法 —— 按日历月算的话，「封 1 个月」在 1 月底和 3 月初
 * 会差出好几天，等长反而更可预期。
 */
public final class BanDuration {

    public static final BanDuration ZERO = new BanDuration(0, 0, 0, 0, 0, 0, 0);

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60L * SECOND;
    private static final long HOUR = 60L * MINUTE;
    private static final long DAY = 24L * HOUR;
    private static final long WEEK = 7L * DAY;
    private static final long MONTH = 30L * DAY;
    private static final long YEAR = 365L * DAY;

    /** 上限 100 年。填错位数（比如年份框里写了手机号）时不至于算出个天文数字 */
    private static final long MAX_MILLIS = 100L * YEAR;

    private final long years;
    private final long months;
    private final long weeks;
    private final long days;
    private final long hours;
    private final long minutes;
    private final long seconds;

    private final long millis;

    private BanDuration(long years, long months, long weeks, long days,
                        long hours, long minutes, long seconds) {
        // 前端传来的值一律当不可信处理，负数按 0 算
        this.years = atLeastZero(years);
        this.months = atLeastZero(months);
        this.weeks = atLeastZero(weeks);
        this.days = atLeastZero(days);
        this.hours = atLeastZero(hours);
        this.minutes = atLeastZero(minutes);
        this.seconds = atLeastZero(seconds);

        this.millis = Math.min(MAX_MILLIS,
                this.years * YEAR
                        + this.months * MONTH
                        + this.weeks * WEEK
                        + this.days * DAY
                        + this.hours * HOUR
                        + this.minutes * MINUTE
                        + this.seconds * SECOND);
    }

    /**
     * 由各分段值构造。任何一段为负都会按 0 处理。
     */
    public static BanDuration of(long years, long months, long weeks, long days,
                                 long hours, long minutes, long seconds) {
        return new BanDuration(years, months, weeks, days, hours, minutes, seconds);
    }

    /** 总时长（毫秒），已按 100 年封顶 */
    public long millis() {
        return millis;
    }

    /** 是否一段都没填 */
    public boolean isZero() {
        return millis <= 0L;
    }

    /**
     * 转成封禁插件认的时长写法，例如 {@code 1y2mo3w4d5h6m7s}，为 0 的分段直接省略。
     *
     * <p>顺序是 y → mo → w → d → h → m → s，{@code mo} 必须排在 {@code m} 前面：
     * 这两个单位都以 m 开头，写反了就是「1 个月」和「1 分钟」的区别。
     */
    public String format() {
        StringBuilder builder = new StringBuilder();
        append(builder, years, "y");
        append(builder, months, "mo");
        append(builder, weeks, "w");
        append(builder, days, "d");
        append(builder, hours, "h");
        append(builder, minutes, "m");
        append(builder, seconds, "s");
        return builder.toString();
    }

    private void append(StringBuilder builder, long value, String unit) {
        if (value > 0) {
            builder.append(value).append(unit);
        }
    }

    private static long atLeastZero(long value) {
        return value < 0L ? 0L : value;
    }
}
