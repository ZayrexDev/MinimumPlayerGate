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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.util.ResourceLeakDetector;
import lombok.Getter;
import ua.nanit.limbo.configuration.LimboConfig;
import ua.nanit.limbo.connection.ClientChannelInitializer;
import ua.nanit.limbo.connection.ClientConnection;
import ua.nanit.limbo.connection.PacketHandler;
import ua.nanit.limbo.connection.PacketSnapshots;
import ua.nanit.limbo.world.DimensionRegistry;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Getter
public final class LimboServer {
    private final LimboConfig config;
    private final CommandHandler<Command> commandHandler;
    private final ClassLoader classLoader;
    private boolean running = false;
    private Channel boundChannel;
    private PacketHandler packetHandler;
    private PacketSnapshots packetSnapshots;
    private Connections connections;
    private DimensionRegistry dimensionRegistry;
    private ScheduledFuture<?> keepAliveTask;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public LimboServer(LimboConfig config, CommandHandler<Command> commandHandler, ClassLoader classLoader) {
        this.config = config;
        this.commandHandler = commandHandler;
        this.classLoader = classLoader;
    }

    public CommandHandler<Command> getCommandManager() {
        return commandHandler;
    }

    public void start() throws Exception {
        Log.setLevel(config.getDebugLevel());
        Log.info("Starting server...");

        if (System.getProperty("io.netty.leakDetectionLevel") == null && System.getProperty("io.netty.leakDetection.level") == null) {
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
        }

        packetHandler = new PacketHandler(this);
        dimensionRegistry = new DimensionRegistry(classLoader);
        dimensionRegistry.load();
        connections = new Connections(config);


        packetSnapshots = new PacketSnapshots(this);

        startBootstrap();

        keepAliveTask = workerGroup.scheduleAtFixedRate(this::broadcastKeepAlive, 0L, 5L, TimeUnit.SECONDS);

        Log.info("Server started on %s", config.getAddress());

        Log.setLevel(config.getDebugLevel());


        running = true;
    }

    private void startBootstrap() {
        TransportType transportType = config.getTransportType();
        if (!transportType.isAvailable()) {
            Log.debug("Transport type " + transportType.name() + " is not available! Using NIO.");
            transportType = TransportType.NIO;
        }

        Log.debug("Using " + transportType.name() + " transport type");

        ChannelFactory<? extends ServerChannel> channelFactory = transportType.getChannelFactory();
        IoHandlerFactory ioHandlerFactory = transportType.getIoHandlerFactory();

        bossGroup = new MultiThreadIoEventLoopGroup(config.getBossGroupSize(), ioHandlerFactory);
        workerGroup = new MultiThreadIoEventLoopGroup(config.getWorkerGroupSize(), ioHandlerFactory);

        boundChannel = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channelFactory(channelFactory)
                .childHandler(new ClientChannelInitializer(this))
                .childOption(ChannelOption.TCP_NODELAY, true)
                .localAddress(config.getAddress())
                .bind().syncUninterruptibly().channel();
    }

    private void broadcastKeepAlive() {
        connections.getAllConnections().forEach(ClientConnection::sendKeepAlive);
    }

    public void stop() {
        Log.info("Stopping server...");

        if (keepAliveTask != null) {
            keepAliveTask.cancel(true);
        }

        if (boundChannel != null) boundChannel.close().syncUninterruptibly();
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }

        running = false;
        Log.info("Server stopped, Goodbye!");
    }

    public boolean isRunning() {
        return running;
    }
}

