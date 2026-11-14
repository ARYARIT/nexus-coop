package dev.nexusmc.coop.mixin;

import java.net.InetAddress;

import dev.nexusmc.coop.NexusCoop;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerConnectionListener.class)
public abstract class ServerConnectionListenerMixin {
    /** Мир, открытый лаунчером, слушает только этот компьютер, а не всю домашнюю сеть. */
    @ModifyVariable(method = "startTcpServerListener", at = @At("HEAD"), argsOnly = true, require = 0)
    private InetAddress nexus$loopbackOnly(InetAddress address) {
        return NexusCoop.ENABLED ? InetAddress.getLoopbackAddress() : address;
    }
}
