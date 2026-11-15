package dev.nexusmc.coop.mixin;

import java.util.function.BooleanSupplier;

import dev.nexusmc.coop.NexusCoop;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    @Inject(method = "tickServer", at = @At("HEAD"), require = 0)
    private void nexus$tick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        NexusCoop.tick((IntegratedServer) (Object) this);
    }

    @Inject(method = "stopServer", at = @At("HEAD"), require = 0)
    private void nexus$stop(CallbackInfo ci) {
        NexusCoop.stop();
    }
}
