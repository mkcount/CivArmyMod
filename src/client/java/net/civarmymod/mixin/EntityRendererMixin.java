package net.civarmymod.mixin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.civarmymod.NPCManager;
import net.civarmymod.NPCChunkManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;

/**
 * 엔티티 렌더링 시 NPC 여부를 확인하는 Mixin
 * NPC인 경우 렌더링 방식을 변경할 수 있습니다.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {
    
    private static final Logger LOGGER = LogManager.getLogger("CivArmyMod/EntityRendererMixin");
    private static final boolean DEBUG_MODE = true;
    
    /**
     * 엔티티 렌더링 시 호출되는 메서드
     * NPC 여부를 확인하고 처리합니다.
     */
    @Inject(method = "updateRenderState", at = @At("RETURN"))
    private void onUpdateRenderState(T entity, S state, float tickDelta, CallbackInfo ci) {
        try {
            // PlayerEntity만 처리 (NPC는 일반적으로 PlayerEntity로 구현됨)
            if (entity instanceof PlayerEntity) {
                PlayerEntity player = (PlayerEntity) entity;
                
                LOGGER.info("[엔티티 검사] 플레이어 엔티티 감지: " + player.getName().getString());
                
                // UUID 확인
                if (player.getUuid() != null) {
                    LOGGER.info("[엔티티 UUID] " + player.getName().getString() + "\uc758 UUID: " + player.getUuid());
                    
                    // NPC 확인
                    boolean isNpc = NPCManager.getInstance().isNpcUuid(player.getUuid());
                    LOGGER.info("[엔티티 NPC 확인] " + player.getName().getString() + " NPC 여부: " + isNpc);
                    
                    if (isNpc) {
                        // NPC로 확인됨
                        LOGGER.info("[NPC 감지] " + player.getName().getString() + " (UUID: " + player.getUuid() + ") - 위치: (" + 
                                player.getX() + ", " + player.getY() + ", " + player.getZ() + ")");
                        
                        // 청크 좌표 계산
                        int chunkX = (int)Math.floor(player.getX()) >> 4;
                        int chunkZ = (int)Math.floor(player.getZ()) >> 4;
                        LOGGER.info("[NPC 청크 위치] " + player.getName().getString() + "\uc758 청크 좌표: (" + 
                                chunkX + ", " + chunkZ + ")");
                        
                        // NPC청크매니저 초기화 및 청크 상태 업데이트
                        try {
                            LOGGER.info("[NPCChunkManager 초기화] NPCChunkManager 싱글톤 인스턴스 가져오기 시도");
                            
                            // NPCChunkManager 싱글톤 인스턴스 가져오기
                            NPCChunkManager chunkManager = NPCChunkManager.getInstance();
                            
                            LOGGER.info("[NPCChunkManager 초기화] 성공적으로 초기화됨");
                            
                            // 청크 상태 업데이트는 NPCChunkManager의 스케줄러에서 자동으로 수행됨
                            LOGGER.info("[NPCChunkManager 스케줄러] 청크 상태 업데이트가 " + 
                                    NPCChunkManager.UPDATE_INTERVAL_MS + "ms 간격으로 자동 수행됩니다.");
                        } catch (Exception e) {
                            LOGGER.error("[NPCChunkManager 오류] 초기화 중 오류 발생: " + e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("NPC 렌더링 상태 처리 중 오류 발생: " + e.getMessage(), e);
        }
    }
    
    /**
     * 엔티티 이름표 렌더링 시 호출되는 메서드
     * NPC인 경우 이름표를 수정합니다.
     */
    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"), cancellable = true)
    private void onRenderLabel(S state, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, 
                              int light, CallbackInfo ci) {
        try {
            // 현재 렌더링 중인 엔티티 정보는 state에 있음
            // 하지만 state에서 직접 엔티티 객체를 가져올 수 없음
            // 따라서 이름 텍스트를 기반으로 NPC 여부 판단 (제한적)
            
            // 이름 텍스트에서 NPC 여부 확인 (예시)
            String name = text.getString();
            
            // 디버그 로그
            if (DEBUG_MODE) {
                LOGGER.debug("이름표 렌더링: " + name);
            }
            
            // 여기서 NPC 이름 형식을 확인하거나 다른 방법으로 NPC 여부 판단 가능
            // 예: 이름에 [NPC] 접두사가 있는지 확인
            if (name.contains("[NPC]")) {
                // NPC로 확인됨 - 이름표 수정 가능
                if (DEBUG_MODE) {
                    LOGGER.debug("NPC 이름표 감지됨: " + name);
                }
                
                // 이름표 렌더링 취소 예시 (필요시 주석 해제)
                // ci.cancel(); // 기본 이름표 렌더링 취소
                
                // 커스텀 이름표 렌더링 로직은 여기에 구현
            }
        } catch (Exception e) {
            LOGGER.error("NPC 이름표 처리 중 오류 발생: " + e.getMessage(), e);
        }
    }
}
