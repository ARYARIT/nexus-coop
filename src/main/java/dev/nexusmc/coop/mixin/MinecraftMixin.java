package dev.nexusmc.coop.mixin;

import dev.nexusmc.coop.NexusPerf;
import dev.nexusmc.coop.NexusPerfMc;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    // без аргументов цели: сигнатура runTick отличается в разных версиях игры
    @Inject(method = "runTick", at = @At("HEAD"), require = 0)
    private void nexus$frame(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        if (NexusPerf.frame()) NexusPerfMc.sample(mc);
        NexusPerfMc.pose(mc);
        NexusPerfMc.avatar(mc);
    }
}
