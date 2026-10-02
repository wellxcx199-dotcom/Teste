package br.climate.client.mixin;

import br.climate.client.ClientClimate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Esconde as nuvens vanilla enquanto as nuvens simuladas (CloudRenderer) estão ativas. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void climamod$hideVanillaClouds(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.dimension() == Level.OVERWORLD && ClientClimate.active()) ci.cancel();
    }
}
