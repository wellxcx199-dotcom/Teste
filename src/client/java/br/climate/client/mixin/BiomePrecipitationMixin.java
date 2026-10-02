package br.climate.client.mixin;

import br.climate.client.ClientClimate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chuva ou neve passa a depender da temperatura simulada (e da altura), não do bioma.
 * Só vale para a renderização no cliente: a thread do servidor integrado continua usando
 * a regra vanilla, para não mudar onde a neve se acumula no mundo.
 */
@Mixin(Biome.class)
public abstract class BiomePrecipitationMixin {
    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
    private void climamod$precipitation(BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !mc.isSameThread() || mc.level == null || mc.level.dimension() != Level.OVERWORLD
                || !ClientClimate.active()) return;
        cir.setReturnValue(ClientClimate.snowAt(pos.getY()) ? Biome.Precipitation.SNOW : Biome.Precipitation.RAIN);
    }
}
