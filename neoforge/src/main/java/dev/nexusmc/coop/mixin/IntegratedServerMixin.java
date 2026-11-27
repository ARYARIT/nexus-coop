package dev.nexusmc.coop.mixin;

import java.util.function.BooleanSupplier;

import dev.nexusmc.coop.NexusCoop;
import dev.nexusmc.coop.NexusPerf;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    @Inject(method = "tickServer", at = @At("HEAD"), require = 0)
    private void nexus$tick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        NexusPerf.serverTickStart();
        NexusCoop.tick((IntegratedServer) (Object) this);
    }

    @Inject(method = "tickServer", at = @At("RETURN"), require = 0)
    private void nexus$tickEnd(CallbackInfo ci) {
        NexusPerf.serverTickEnd();
    }

    @Inject(method = "stopServer", at = @At("HEAD"), require = 0)
    private void nexus$stop(CallbackInfo ci) {
        NexusPerf.serverStopped();
        NexusCoop.stop();
    }
}
