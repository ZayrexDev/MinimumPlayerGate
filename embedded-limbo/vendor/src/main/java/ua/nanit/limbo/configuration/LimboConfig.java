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

package ua.nanit.limbo.configuration;

import java.net.SocketAddress;

import net.kyori.adventure.text.Component;

import ua.nanit.limbo.server.TransportType;
import ua.nanit.limbo.server.data.BossBar;
import ua.nanit.limbo.server.data.InfoForwarding;
import ua.nanit.limbo.server.data.PingData;
import ua.nanit.limbo.server.data.Title;
import ua.nanit.limbo.world.DimensionType;

public interface LimboConfig {
    SocketAddress getAddress();

    int getMaxPlayers();

    PingData getPingData();

    DimensionType getDimensionType();

    int getGameMode();

    boolean isSecureProfile();

    InfoForwarding getInfoForwarding();

    long getReadTimeout();

    int getDebugLevel();

    boolean isLogPlayersIp();

    boolean isUseBrandName();

    boolean isUseJoinMessage();

    boolean isUseBossBar();

    boolean isUseTitle();

    boolean isUsePlayerList();

    boolean isUseHeaderAndFooter();

    Component getBrandName();

    Component getJoinMessage();

    BossBar getBossBar();

    Title getTitle();

    String getPlayerListUsername();

    Component getPlayerListHeader();

    Component getPlayerListFooter();

    TransportType getTransportType();

    int getBossGroupSize();

    int getWorkerGroupSize();

    default boolean isUseTrafficLimits() {
        return false;
    }

    default int getMaxPacketSize() {
        return -1;
    }

    default int getMaxPacketsPerSec() {
        return -1;
    }

    default int getMaxBytesPerSec() {
        return -1;
    }

    double getInterval();

    double getMaxPacketRate();

    double getMaxPacketBytesRate();

}
