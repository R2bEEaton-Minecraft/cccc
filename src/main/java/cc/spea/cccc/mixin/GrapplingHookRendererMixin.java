package cc.spea.cccc.mixin;

import cc.spea.cccc.compat.CreateHookRenderAttachment;
import com.github.eterdelta.crittersandcompanions.client.renderer.GrapplingHookRenderer;
import com.github.eterdelta.crittersandcompanions.entity.GrapplingHookEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GrapplingHookRenderer.class)
public abstract class GrapplingHookRendererMixin {

    @Unique private Vec3 cccc_renderCreateHookPos;

    @Inject(
        method = "render(Lcom/github/eterdelta/crittersandcompanions/entity/GrapplingHookEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V",
            ordinal = 0,
            shift = At.Shift.AFTER
        )
    )
    private void cccc_translateToCreatePartialPosition(
        GrapplingHookEntity hook,
        float yaw,
        float partialTicks,
        PoseStack poseStack,
        MultiBufferSource buffers,
        int packedLight,
        CallbackInfo ci
    ) {
        cccc_renderCreateHookPos = ((CreateHookRenderAttachment) hook).cccc$getCreateHookRenderPosition(partialTicks);
        if (cccc_renderCreateHookPos == null) return;

        double vanillaX = Mth.lerp(partialTicks, hook.xo, hook.getX());
        double vanillaY = Mth.lerp(partialTicks, hook.yo, hook.getY());
        double vanillaZ = Mth.lerp(partialTicks, hook.zo, hook.getZ());
        poseStack.translate(
            cccc_renderCreateHookPos.x - vanillaX,
            cccc_renderCreateHookPos.y - vanillaY,
            cccc_renderCreateHookPos.z - vanillaZ
        );
    }

    @Inject(
        method = "render(Lcom/github/eterdelta/crittersandcompanions/entity/GrapplingHookEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("RETURN")
    )
    private void cccc_clearCreatePartialPosition(
        GrapplingHookEntity hook,
        float yaw,
        float partialTicks,
        PoseStack poseStack,
        MultiBufferSource buffers,
        int packedLight,
        CallbackInfo ci
    ) {
        cccc_renderCreateHookPos = null;
    }

    @Redirect(
        method = "render(Lcom/github/eterdelta/crittersandcompanions/entity/GrapplingHookEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(DDD)D", ordinal = 5)
    )
    private double cccc_renderHookStringX(double delta, double start, double end) {
        return cccc_renderCreateHookPos == null ? Mth.lerp(delta, start, end) : cccc_renderCreateHookPos.x;
    }

    @Redirect(
        method = "render(Lcom/github/eterdelta/crittersandcompanions/entity/GrapplingHookEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(DDD)D", ordinal = 6)
    )
    private double cccc_renderHookStringY(double delta, double start, double end) {
        return cccc_renderCreateHookPos == null ? Mth.lerp(delta, start, end) : cccc_renderCreateHookPos.y;
    }

    @Redirect(
        method = "render(Lcom/github/eterdelta/crittersandcompanions/entity/GrapplingHookEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(DDD)D", ordinal = 7)
    )
    private double cccc_renderHookStringZ(double delta, double start, double end) {
        return cccc_renderCreateHookPos == null ? Mth.lerp(delta, start, end) : cccc_renderCreateHookPos.z;
    }
}
