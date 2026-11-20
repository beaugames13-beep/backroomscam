package com.example.backroomscam.mixin;

import com.example.backroomscam.client.BackroomsCamClient;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @ModifyReturnValue(method = "getFov", at = @At("RETURN"))
    private float backroomscam$zoom(float original) {
        return original * (1.0f - 0.65f * BackroomsCamClient.zoom);
    }
}
