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

package ua.nanit.limbo.server.commands;

import lombok.AllArgsConstructor;
import org.jspecify.annotations.NonNull;
import ua.nanit.limbo.server.Command;
import ua.nanit.limbo.server.LimboServer;

@AllArgsConstructor
public class CmdStop implements Command {

    private final LimboServer server;

    @Override
    public void execute() {
        server.stop();
    }

    @Override
    public @NonNull String name() {
        return "stop";
    }

    @Override
    public @NonNull String description() {
        return "Stop the server";
    }

}
