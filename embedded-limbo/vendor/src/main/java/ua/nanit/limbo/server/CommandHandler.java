package ua.nanit.limbo.server;

import lombok.NonNull;

import java.util.Collection;

public interface CommandHandler<T> {
    @NonNull
    Collection<Command> getCommands();

    void register(@NonNull T command);

    boolean executeCommand(String input);
}
