package br.climate.mod.mixin;

import br.climate.mod.ClimateMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marca o intervalo em que o servidor decide neve, gelo e chuva nos caldeirões. Só dentro
 * dele o BiomeClimateMixin troca a temperatura do bioma pela simulada; fora (por exemplo,
 * na geração de mundo) o jogo continua usando o bioma.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelPrecipitationMixin {
    @Inject(method = "tickPrecipitation", at = @At("HEAD"))
    private void climamod$begin(BlockPos pos, CallbackInfo ci) {
        ClimateMod.beginPrecipitation((ServerLevel) (Object) this);
    }

    @Inject(method = "tickPrecipitation", at = @At("RETURN"))
    private void climamod$end(BlockPos pos, CallbackInfo ci) {
        ClimateMod.endPrecipitation();
    }
}
