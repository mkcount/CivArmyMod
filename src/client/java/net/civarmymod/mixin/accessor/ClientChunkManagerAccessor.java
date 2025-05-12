package net.civarmymod.mixin.accessor;

import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor Mixin for ClientChunkManager to expose forceUnloadChunk.
 */
@Mixin(net.minecraft.client.world.ClientChunkManager.class)
public interface ClientChunkManagerAccessor {
    @Invoker("unload")
    void invokeUnload(ChunkPos pos);

    // Although forceUnloadChunk is defined in the Mixin,
    // it calls the original unload method.
    // We can expose the original unload method via an Invoker
    // and call that from FogOfWarClient.
    // Alternatively, we could define forceUnloadChunk in this interface
    // and implement it in the Mixin, but exposing the original method is cleaner.
}
