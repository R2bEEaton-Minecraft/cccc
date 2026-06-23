package cc.spea.cccc;

import cc.spea.cccc.compat.SableGrappleHandler;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Create x Critters and Companions Compat (cccc)
 *
 * Uses a Mixin on GrapplingHookEntity to detect when the hook lands on a
 * Create contraption block and, from that point on, keeps the hook glued to
 * the contraption's local coordinate frame so it moves/rotates with it.
 *
 * @author R2bEEaton
 */
@Mod(CCCC.MODID)
public class CCCC {

    public static final String MODID = "cccc";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CCCC(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(SableGrappleHandler::onPlayerTick);
        LOGGER.info("[cccc] Create x Critters & Companions Compat loaded");
    }
}
