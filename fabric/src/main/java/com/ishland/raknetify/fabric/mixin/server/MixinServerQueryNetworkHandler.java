/*
 * This file is a part of the Raknetify project, licensed under MIT.
 *
 * Copyright (c) 2022-2025 ishland
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.ishland.raknetify.fabric.mixin.server;

import com.ishland.raknetify.fabric.mixin.access.IClientConnection;
import io.netty.channel.Channel;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import net.minecraft.server.network.ServerQueryNetworkHandler;
import net.minecraft.text.Text;
import network.ycc.raknet.RakNet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(ServerQueryNetworkHandler.class)
public class MixinServerQueryNetworkHandler {

    @Shadow @Final private ClientConnection connection;
    @Shadow @Final private static Text REQUEST_HANDLED;

    @Inject(method = "onQueryPing", at = @At("HEAD"), cancellable = true)
    private void acknowledgePongBeforeClosing(QueryPingC2SPacket packet, CallbackInfo ci) {
        final Channel channel = ((IClientConnection) this.connection).getChannel();
        if (!(channel.config() instanceof RakNet.Config)) return;

        // Disconnect uses channel 7 and can overtake the pong on channel 0.
        // Reliable write completion means the pong's frame was acknowledged.
        final AtomicBoolean closed = new AtomicBoolean();
        final Runnable close = () -> {
            if (closed.compareAndSet(false, true)) {
                this.connection.disconnect(REQUEST_HANDLED);
            }
        };
        // Bound cleanup for clients that never acknowledge, without blocking.
        final var timeout = channel.eventLoop().schedule(close, 3, TimeUnit.SECONDS);
        this.connection.send(new PingResultS2CPacket(packet.getStartTime()), future -> {
            timeout.cancel(false);
            close.run();
        });
        ci.cancel();
    }
}
