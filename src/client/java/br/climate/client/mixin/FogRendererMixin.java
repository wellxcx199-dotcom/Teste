package br.climate.client.mixin;

import br.climate.client.ClientClimate;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Neblina simulada: aproxima a névoa do terreno quando a célula do jogador está com FOG. */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    /** Visibilidade na neblina mais densa, em blocos. */
    private static final float CLIMAMOD$FOG_END = 24f;

    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void climamod$fog(Camera camera, FogRenderer.FogMode mode, float farPlane, boolean thick,
                                     float partialTick, CallbackInfo ci) {
        if (mode != FogRenderer.FogMode.FOG_TERRAIN || camera.getFluidInCamera() != FogType.NONE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != Level.OVERWORLD || !ClientClimate.active()) return;
        float f = ClientClimate.fogLevel(partialTick);
        if (f <= 0) return;
        RenderSystem.setShaderFogStart(Mth.lerp(f, RenderSystem.getShaderFogStart(), 0f));
        RenderSystem.setShaderFogEnd(Mth.lerp(f, RenderSystem.getShaderFogEnd(), CLIMAMOD$FOG_END));
    }
}
