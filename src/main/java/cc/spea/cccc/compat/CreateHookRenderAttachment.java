package cc.spea.cccc.compat;

import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

public interface CreateHookRenderAttachment {
    @Nullable
    Vec3 cccc$getCreateHookRenderPosition(float partialTicks);
}
