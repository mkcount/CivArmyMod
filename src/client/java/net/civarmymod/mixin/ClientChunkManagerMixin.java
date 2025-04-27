package net.civarmymod.mixin;
import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.civarmymod.FogOfWarClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

/**
 * 클라이언트 청크 관리자를 가로채는 Mixin
 * 안개 상태 청크는 과거 스냅샷으로 렌더링합니다.
 */
@Environment(EnvType.CLIENT)
@Mixin(ClientChunkManager.class)
public abstract class ClientChunkManagerMixin {
    
    @Shadow
    private ClientWorld world;
    
    /**
     * 특정 청크를 강제로 언로드합니다.
     */
    public void forceUnloadChunk(int x, int z) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.world != null) {
            ChunkPos pos = new ChunkPos(x, z);
            ((ClientChunkManager)client.world.getChunkManager()).unload(pos);
            System.out.println("청크 강제 언로드: (" + x + ", " + z + ")");
        }
    }
    
    /**
     * 청크 데이터 패킷 처리를 가로채서 안개 상태 청크는 처리 방식을 변경합니다.
     */
    @Inject(method = "loadChunkFromPacket", at = @At("HEAD"), cancellable = true)
    private void onLoadChunkFromPacket(int x, int z, PacketByteBuf buf, NbtCompound nbt, 
                                      Consumer<ChunkData.BlockEntityVisitor> consumer,
                                      CallbackInfoReturnable<WorldChunk> cir) {
        try {
            // FogOfWarClient 인스턴스 확인
            if (FogOfWarClient.getInstance() == null) {
                return;
            }
            
        
            
            // 안개 기능이 활성화되었고 현재 청크가 숨겨진 상태인지 확인
            if ( FogOfWarClient.isHiddenChunk(x, z)) {
                // 숨겨진 청크는 로드하지 않고 null 반환
                System.out.println("숨겨진 청크 로드 요청 무시: (" + x + ", " + z + ")");
                cir.setReturnValue(null);
                return;
            }
            
            // 현재 청크가 안개 상태인지 확인
            if (FogOfWarClient.isFoggedChunk(x, z)) {
                // 안개 상태 청크의 경우 스냅샷에서 데이터를 가져오거나 수정
                NbtCompound snapshot = FogOfWarClient.getChunkSnapshot(x, z);
                if (snapshot != null) {
                    // 여기서는 표시만 수정하므로 실제 로직은 구현하지 않고 
                    // 기존 로직을 계속 진행하도록 합니다.
                    // 실제 구현에서는 스냅샷의 데이터로 현재 패킷의 데이터를 수정할 수 있습니다.
                    System.out.println("안개 청크 데이터 패킷 수신: (" + x + ", " + z + ")");
                }
            }
        } catch (Exception e) {
            System.out.println("ClientChunkManagerMixin 오류: " + e.getMessage());
        }
    }
}
