package br.climate.client.mixin;

import br.climate.client.ClientClimate;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No cliente, a intensidade da chuva e da trovoada passa a ser a do clima local do jogador
 * (vinda do servidor), e não a global do mundo. Isso controla partículas de chuva, som,
 * escurecimento do céu e relâmpagos visuais. O mundo do servidor não é afetado.
 */
@Mixin(Level.class)
public abstract class LevelWeatherMixin {
    private boolean climamod$local() {
        Level self = (Level) (Object) this;
        return self.isClientSide() && self.dimension() == Level.OVERWORLD && ClientClimate.active();
    }

    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void climamod$rain(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (climamod$local()) cir.setReturnValue(ClientClimate.rainLevel(partialTick));
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    private void climamod$thunder(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (climamod$local()) cir.setReturnValue(ClientClimate.thunderLevel(partialTick));
    }
}
