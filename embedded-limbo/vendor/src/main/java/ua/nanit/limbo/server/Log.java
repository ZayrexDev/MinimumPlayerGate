/*
 * Copyright (C) 2020 Nan1t
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ua.nanit.limbo.server;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@UtilityClass
public final class Log {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("hh:mm:ss");
    private static int debugLevel = Level.INFO.getIndex();

    public static int getLevel() {
        return debugLevel;
    }

    public static void info(@NonNull Object msg, @Nullable Object... args) {
        print(Level.INFO, msg, null, args);
    }

    public static void debug(@NonNull Object msg, @Nullable Object... args) {
        print(Level.DEBUG, msg, null, args);
    }

    public static void warning(@NonNull Object msg, @Nullable Object... args) {
        print(Level.WARNING, msg, null, args);
    }

    public static void warning(@NonNull Object msg, @NonNull Throwable t, @Nullable Object... args) {
        print(Level.WARNING, msg, t, args);
    }

    public static void error(@NonNull Object msg, @Nullable Object... args) {
        print(Level.ERROR, msg, null, args);
    }

    public static void error(@NonNull Object msg, @NonNull Throwable t, @Nullable Object... args) {
        print(Level.ERROR, msg, t, args);
    }

    public static void print(Level level, @NonNull Object msg, @Nullable Throwable t, @Nullable Object... args) {
        if (debugLevel >= level.getIndex()) {
            String output = String.format("%s: %s", getPrefix(level), String.format(msg.toString(), args));
            System.out.print(output);
            if (t != null) t.printStackTrace();
        }
    }

    public static boolean isDebug() {
        return debugLevel >= Level.DEBUG.getIndex();
    }

    private static String getPrefix(Level level) {
        return String.format("[%s] [%s]", getTime(), level.getDisplay());
    }

    private static String getTime() {
        return LocalTime.now().format(FORMATTER);
    }

    static void setLevel(int level) {
        debugLevel = level;
    }

    @AllArgsConstructor
    @Getter
    public enum Level {
        ERROR("ERROR", 0),
        WARNING("WARNING", 1),
        INFO("INFO", 2),
        DEBUG("DEBUG", 3);

        private final String display;
        private final int index;
    }
}
