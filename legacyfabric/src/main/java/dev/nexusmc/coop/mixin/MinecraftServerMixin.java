package dev.nexusmc.coop.mixin;

import dev.nexusmc.coop.NexusCoop;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void nexus$tick(CallbackInfo ci) {
        Object self = this;
        if (self instanceof IntegratedServer) NexusCoop.tick((IntegratedServer) self);
    }
}
