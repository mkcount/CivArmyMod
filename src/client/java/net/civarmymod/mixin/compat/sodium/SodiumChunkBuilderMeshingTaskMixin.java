package net.civarmymod.mixin.compat.sodium;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.civarmymod.FogOfWarClient;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/**
 * 청크 상태에 따라 블록 렌더링을 수정
 * - HIDDEN: 모든 블록을 공기로 변경 (완전히 숨김)
 * - FOGGED: 모든 블록을 흐흑 블록으로 변경 (과거 위치 유지)
 * - VISIBLE: 원래 블록 상태 유지
 */
@Mixin(ChunkBuilderMeshingTask.class)
public class SodiumChunkBuilderMeshingTaskMixin {
    
    private static final Logger LOGGER = LogManager.getLogger("CivArmyMod/SodiumCompatMixin");

    static {
        LOGGER.info("SodiumChunkBuilderMeshingTaskMixin 클래스가 로드되었습니다!");
    }

    /**
     * 청크 상태에 따라 블록 렌더링을 수정
     * - HIDDEN: 모든 블록을 공기로 변경 (완전히 숨김)
     * - FOGGED: 모든 블록을 흐흑 블록으로 변경 (과거 위치 유지)
     * - VISIBLE: 원래 블록 상태 유지
     */
    @Redirect(
            method = "execute",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;getBlockState(III)Lnet/minecraft/block/BlockState;"
            )
    )
    private BlockState redirectGetBlockState(LevelSlice slice, int x, int y, int z) {
        try {
            // 원래 블록 상태 가져오기
            BlockState originalState = slice.getBlockState(x, y, z);

            // 공기 블록은 그대로 유지
            if (originalState == null || originalState.isAir()) {
                return originalState;
            }
            
            // 청크 좌표 계산
            int chunkX = x >> 4;
            int chunkZ = z >> 4;

            FogOfWarClient fogClient = FogOfWarClient.getInstance();
            if (fogClient != null) {
                try {
                    
                    
                    // FOGGED 상태 확인 (과거 블록 위치 유지, 흐흑 블록으로 바꾸기)
                    if (fogClient.isFoggedChunk(chunkX, chunkZ)) {
                        // 원래 블록이 존재하는지 확인 (스냅샷에서)
                        BlockState snapshotState = fogClient.getOriginalBlockState(x, y, z);
                                                
                        if (snapshotState != null) {
                            if (snapshotState.isOf(Blocks.STONE)) { // FogOfClient.getOriginalBlockState에서 고체를 STONE으로 반환
                                // 스냅샷에 고체 블록이 있으면 흙으로 바꾸기
                                return Blocks.DIRT.getDefaultState();
                            } else if (snapshotState.isOf(Blocks.WATER)) { // FogOfClient.getOriginalBlockState에서 액체를 WATER로 반환
                                // 스냅샷에 액체 블록이 있으면 돌로 바꾸기
                                return Blocks.STONE.getDefaultState();
                            } else { // 스냅샷에 공기 또는 기타 블록 (Blocks.AIR 반환됨)
                                return Blocks.AIR.getDefaultState();
                            }
                        } else {
                            // 스냅샷 정보가 없는 경우 (getOriginalBlockState가 null 반환)
                            // 이는 Y 레벨이 스냅샷 범위를 벗어났거나, 스냅샷 자체가 없는 경우 등
                            // 이 경우 해당 위치는 공기로 처리하여 빈 공간으로 남김
                            LOGGER.info("FOGGED Chunk: (" + chunkX + "," + chunkZ + ") at (" + x + "," + y + "," + z + ") -> " + snapshotState.getBlock().getTranslationKey());
                            
                            return Blocks.AIR.getDefaultState();
                            

                        }
                    }
                    if (fogClient.isVisibleChunk(chunkX, chunkZ)){
                        return originalState;
                    }else{
                        return Blocks.AIR.getDefaultState();
                    }

                } catch (Exception e) {
                    LOGGER.error("Fog of War 청크 상태 처리 중 오류: " + e.getMessage(), e);
                    // 오류 발생 시 원래 블록 상태 반환
                    return originalState;
                }
            }

            // VISIBLE 상태 또는 기타 경우에는 원래 블록 상태 반환
            return originalState;
        } catch (Exception e) {
            LOGGER.error("Fog of War Mixin 오류: " + e.getMessage(), e);
            // 오류 발생 시 원래 메서드 호출 시도
            try {
                return slice.getBlockState(x, y, z);
            } catch (Exception ex) {
                // 정말 심각한 오류일 경우 공기 블록 반환
                return Blocks.AIR.getDefaultState();
            }
        }
    }
}