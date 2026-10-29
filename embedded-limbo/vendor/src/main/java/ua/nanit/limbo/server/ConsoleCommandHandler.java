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

import org.jspecify.annotations.NonNull;
import ua.nanit.limbo.server.commands.CmdConn;
import ua.nanit.limbo.server.commands.CmdHelp;
import ua.nanit.limbo.server.commands.CmdMem;
import ua.nanit.limbo.server.commands.CmdStop;

import java.util.*;

public final class ConsoleCommandHandler extends Thread implements CommandHandler<Command> {

    private final Map<String, Command> commands = new HashMap<>();

    public Command getCommand(String name) {
        return commands.get(name.toLowerCase());
    }

    @Override
    public void register(@NonNull Command cmd) {
        commands.put(cmd.name().toLowerCase(), cmd);
    }

    @Override
    public boolean executeCommand(String input) {
        Command handler = getCommand(input);

        if (handler != null) {
            try {
                handler.execute();
            } catch(Throwable t) {
                Log.error("Cannot execute command:", t);
            }
            return true;
        }

        Log.info("Unknown command. Type \"help\" to get commands list");
        return false;
    }

    @Override
    public @NonNull Collection<Command> getCommands() {
        return Collections.unmodifiableCollection(commands.values());
    }

    @Override
    public void run() {
        Scanner scanner = new Scanner(System.in);
        String command;

        while(true) {
            try {
                command = scanner.nextLine().trim();
            } catch(NoSuchElementException e) {
                break;
            }

            executeCommand(command);
        }
    }

    public ConsoleCommandHandler registerAll(LimboServer server) {
        register(new CmdHelp(server));
        register(new CmdConn(server));
        register(new CmdMem());
        register(new CmdStop(server));
        return this;
    }
}
