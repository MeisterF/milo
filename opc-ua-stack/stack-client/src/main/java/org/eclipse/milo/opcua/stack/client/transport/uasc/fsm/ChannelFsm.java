/*
 * Copyright (c) 2018 Kevin Herron
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *   http://www.eclipse.org/org/documents/edl-v10.html.
 */

package org.eclipse.milo.opcua.stack.client.transport.uasc.fsm;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nullable;

import io.netty.channel.Channel;
import org.eclipse.milo.opcua.stack.client.UaStackClientConfig;
import org.eclipse.milo.opcua.stack.client.transport.uasc.fsm.events.Connect;
import org.eclipse.milo.opcua.stack.client.transport.uasc.fsm.events.Disconnect;
import org.eclipse.milo.opcua.stack.client.transport.uasc.fsm.events.GetChannel;
import org.eclipse.milo.opcua.stack.client.transport.uasc.fsm.states.Connected;
import org.eclipse.milo.opcua.stack.client.transport.uasc.fsm.states.NotConnected;
import org.eclipse.milo.opcua.stack.core.util.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.milo.opcua.stack.core.util.FutureUtils.complete;

public class ChannelFsm {

    private static final AtomicLong ID_SEQUENCE = new AtomicLong(0L);

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final Long id = ID_SEQUENCE.getAndIncrement();

    private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock(true);

    private final Context context = new Context();

    private final AtomicReference<State> state = new AtomicReference<>(new NotConnected());

    private final UaStackClientConfig config;

    public ChannelFsm(UaStackClientConfig config) {
        this.config = config;
    }

    public CompletableFuture<Channel> connect() {
        logger.debug("[{}] connect()", getId());

        Connect connect = new Connect();

        fireEvent(connect);

        return complete(new CompletableFuture<Channel>())
            .async(config.getExecutor())
            .with(connect.getChannelFuture());
    }

    public CompletableFuture<Unit> disconnect() {
        logger.debug("[{}] disconnect()", getId());

        Disconnect disconnect = new Disconnect();

        fireEvent(disconnect);

        return complete(new CompletableFuture<Unit>())
            .async(config.getExecutor())
            .with(disconnect.getDisconnectFuture());
    }

    public CompletableFuture<Channel> getChannel() {
        logger.debug("[{}] getChannel()", getId());

        readWriteLock.readLock().lock();
        try {
            State current = state.get();

            if (current instanceof Connected) {
                // "Fast" path... already connected.
                return context.getChannelFuture();
            }
        } finally {
            readWriteLock.readLock().unlock();
        }

        // "Slow" path... not connected yet.
        GetChannel getChannel = new GetChannel();

        fireEvent(getChannel);

        return complete(new CompletableFuture<Channel>())
            .async(config.getExecutor())
            .with(getChannel.getChannelFuture());
    }

    public void fireEvent(Event event) {
        if (readWriteLock.writeLock().isHeldByCurrentThread()) {
            config.getExecutor().execute(() -> fireEvent(event));
        } else {
            readWriteLock.writeLock().lock();

            try {
                State prevState = state.get();

                State nextState = state.updateAndGet(
                    state ->
                        state.execute(this, event)
                );

                logger.debug(
                    "[{}] S({}) x E({}) = S'({})",
                    getId(),
                    prevState.getClass().getSimpleName(),
                    event.getClass().getSimpleName(),
                    nextState.getClass().getSimpleName()
                );

                if (prevState.getClass() == nextState.getClass()) {
                    nextState.onInternalTransition(this, event);
                } else {
                    nextState.onExternalTransition(this, prevState, event);
                }
            } finally {
                readWriteLock.writeLock().unlock();
            }
        }
    }

    public Long getId() {
        return id;
    }

    public Context getContext() {
        return context;
    }

    public UaStackClientConfig getConfig() {
        return config;
    }

    public ExecutorService getExecutorService() {
        return config.getExecutor();
    }

    public boolean isPersistent() {
        return config.isConnectPersistent();
    }

    public class Context {

        private CompletableFuture<Channel> channelFuture;
        private CompletableFuture<Unit> disconnectFuture;
        private long reconnectDelay;

        @Nullable
        public CompletableFuture<Channel> getChannelFuture() {
            readWriteLock.readLock().lock();
            try {
                return channelFuture;
            } finally {
                readWriteLock.readLock().unlock();
            }
        }

        public void setChannelFuture(@Nullable CompletableFuture<Channel> channelFuture) {
            readWriteLock.writeLock().lock();
            try {
                CompletableFuture<Channel> previous = this.channelFuture;
                this.channelFuture = channelFuture;

                if (previous != null && !previous.isDone()) {
                    logger.debug("previous channelFuture replaced without being completed");

                    previous.completeExceptionally(
                        new IllegalStateException(String.format("state=%s", state.get())));
                }
            } finally {
                readWriteLock.writeLock().unlock();
            }
        }

        @Nullable
        public CompletableFuture<Unit> getDisconnectFuture() {
            readWriteLock.readLock().lock();
            try {
                return disconnectFuture;
            } finally {
                readWriteLock.readLock().unlock();
            }
        }

        public void setDisconnectFuture(@Nullable CompletableFuture<Unit> disconnectFuture) {
            readWriteLock.writeLock().lock();
            try {
                CompletableFuture<Unit> previous = this.disconnectFuture;
                this.disconnectFuture = disconnectFuture;

                if (previous != null && !previous.isDone()) {
                    logger.debug("previous disconnectFuture replaced without being completed");

                    previous.completeExceptionally(
                        new IllegalStateException(String.format("state=%s", state.get())));
                }
            } finally {
                readWriteLock.writeLock().unlock();
            }
        }

        public long getReconnectDelay() {
            return reconnectDelay;
        }

        public void setReconnectDelay(long reconnectDelay) {
            this.reconnectDelay = reconnectDelay;
        }

    }

    public interface Event {}

    public abstract static class State {

        protected static final Logger LOGGER = LoggerFactory.getLogger(ChannelFsm.class);

        public abstract State execute(ChannelFsm fsm, Event event);

        public abstract void onInternalTransition(ChannelFsm fsm, Event event);

        public abstract void onExternalTransition(ChannelFsm fsm, State prevState, Event event);

    }
}