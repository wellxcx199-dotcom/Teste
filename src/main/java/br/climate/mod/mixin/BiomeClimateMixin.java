package br.climate.mod.mixin;

import br.climate.core.Physics;
import br.climate.mod.ClimateMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Durante ServerLevel.tickPrecipitation do Overworld, dentro da área simulada:
 * a água congela e a neve se acumula conforme a temperatura simulada (não a do bioma),
 * e a neve só cai onde o modelo diz que está precipitando.
 */
@Mixin(Biome.class)
public abstract class BiomeClimateMixin {
    @Inject(method = "warmEnoughToRain", at = @At("HEAD"), cancellable = true)
    private void climamod$warm(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel level = ClimateMod.precipitationLevel();
        if (level == null) return;
        double t = ClimateMod.modelTemperatureAt(level, pos);
        if (!Double.isNaN(t)) cir.setReturnValue(t >= Physics.SNOW_BELOW_C);
    }

    @Inject(method = "shouldSnow", at = @At("HEAD"), cancellable = true)
    private void climamod$snow(LevelReader reader, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel level = ClimateMod.precipitationLevel();
        if (level == null || Double.isNaN(ClimateMod.modelTemperatureAt(level, pos))) return;
        if (!ClimateMod.modelPrecipitatingAt(pos)) cir.setReturnValue(false);
    }

    /** Caldeirões: enchem com chuva ou neve só se o modelo diz que está precipitando ali. */
    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
    private void climamod$precipitation(BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
        ServerLevel level = ClimateMod.precipitationLevel();
        if (level == null) return;
        double t = ClimateMod.modelTemperatureAt(level, pos);
        if (Double.isNaN(t)) return;
        if (!ClimateMod.modelPrecipitatingAt(pos)) cir.setReturnValue(Biome.Precipitation.NONE);
        else cir.setReturnValue(t < Physics.SNOW_BELOW_C ? Biome.Precipitation.SNOW : Biome.Precipitation.RAIN);
    }
}
