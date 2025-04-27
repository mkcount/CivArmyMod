package net.civarmymod.mixin;
import net.civarmymod.FogOfWarClient;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.chunk.ChunkRendererRegion;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 청크 렌더 영역을 가로채는 Mixin
 * 안개 상태 청크의 블록은 스냅샷에서 가져온 과거 상태로 렌더링합니다.
 */
@Environment(EnvType.CLIENT)
@Mixin(ChunkRendererRegion.class)
public class ChunkRenderRegionMixin {

    /**
     * 블록 상태 조회를 가로채서 안개 상태 청크는 스냅샷 데이터를 반환합니다.
     */
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void onGetBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        try {
            System.out.println("[FogOfWar DEBUG] ChunkRenderRegionMixin.onGetBlockState called for pos: " + pos); // 디버그 로그 추가
            // 청크 좌표 계산
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;
            
            // FogOfWarClient 인스턴스 확인
            if (FogOfWarClient.getInstance() == null) {
                return;
            }
            
            // 현재 청크가 안개 상태인지 확인
            if (FogOfWarClient.isFoggedChunk(chunkX, chunkZ)) {
                // 스냅샷에서 블록 상태 가져오기
                BlockState state = getBlockStateFromSnapshot(chunkX, chunkZ, pos);
                if (state != null) {
                    cir.setReturnValue(state);
                    cir.cancel();
                } else {
                    // 스냅샷이 없으면 기본 안개 블록으로 대체
                    BlockState fogBlock = FogOfWarClient.getFogBlock(chunkX, chunkZ);
                    cir.setReturnValue(fogBlock);
                    cir.cancel();
                }
            }
        } catch (Exception e) {
            System.out.println("ChunkRenderRegionMixin 오류: " + e.getMessage());
        }
    }
    
    /**
     * 스냅샷에서 특정 위치의 블록 상태를 가져옵니다.
     */
    private BlockState getBlockStateFromSnapshot(int chunkX, int chunkZ, BlockPos pos) {
        try {
            NbtCompound snapshot = FogOfWarClient.getChunkSnapshot(chunkX, chunkZ);
            if (snapshot == null) {
                return null;
            }
            
            // 청크 내 상대 좌표 계산
            int relX = pos.getX() & 15;
            int relZ = pos.getZ() & 15;
            int sectionY = pos.getY() >> 4;
            int relY = pos.getY() & 15;
            
            // 섹션에서 블록 찾기
            if (snapshot.contains("sections")) {
                NbtList sections = snapshot.getList("sections", 10);
                for (int i = 0; i < sections.size(); i++) {
                    NbtCompound section = sections.getCompound(i);
                    if (section.getInt("y") == sectionY) {
                        NbtList blocks = section.getList("blocks", 10);
                        for (int j = 0; j < blocks.size(); j++) {
                            NbtCompound block = blocks.getCompound(j);
                            if (block.getInt("x") == relX && block.getInt("y") == relY && block.getInt("z") == relZ) {
                                // 블록 ID로 BlockState 생성
                                try {
                                    String blockId = block.getString("id");
                                    // 안전한 Identifier 생성
                                    return Registries.BLOCK.get(Identifier.of(blockId)).getDefaultState();
                                } catch (Exception e) {
                                    System.out.println("블록 상태 생성 실패: " + e.getMessage());
                                    return Blocks.BEDROCK.getDefaultState(); // 오류 시 암석으로 표시
                                }
                            }
                        }
                    }
                }
            }
            
            return null;
        } catch (Exception e) {
            System.out.println("스냅샷에서 블록 상태 가져오기 실패: " + e.getMessage());
            return null;
        }
    }
}
