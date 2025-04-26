package net.civarmymod.mixin;

import net.civarmymod.FogOfWarClient;
import net.civarmymod.config.FogConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 월드 렌더러를 가로채는 Mixin
 * 안개 상태의 청크에 시각적 효과를 적용합니다.
 */
@Environment(EnvType.CLIENT)
@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

    /**
     * 렌더링 중 안개 청크에 시각적 효과 적용
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void onRender(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, 
                          Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, 
                          Matrix4f matrix4f, CallbackInfo ci) {
        
        Vec3d cameraPos = camera.getPos();
        int chunkX = ((int)cameraPos.x) >> 4;
        int chunkZ = ((int)cameraPos.z) >> 4;
        
        // 현재 청크가 안개 상태인지 확인
        boolean inFoggedChunk = FogOfWarClient.isFoggedChunk(chunkX, chunkZ);
        
        // 디버그 모드에서 로그 출력
        if (FogConfig.getConfig().debugMode && inFoggedChunk) {
            System.out.println("플레이어가 안개 청크 내에 있음: " + chunkX + ", " + chunkZ);
        }
        
        // 안개 효과 적용 (실제 안개 효과 구현은 게임 내 안개 렌더링 파이프라인을 통해 처리됩니다)
        // 여기서는 예시로 안개 효과를 활성화할 수 있는 훅을 제공합니다
    }
    
    /**
     * 렌더링 레이어에 안개 효과 적용
     */
    @Inject(method = "renderLayer", at = @At("HEAD"))
    private void onRenderLayer(CallbackInfo ci) {
        // 안개 효과를 위한 추가 렌더링 코드
        // 이 부분은 실제 구현에서 셰이더 등을 사용하여 안개 효과를 적용할 수 있습니다
    }
}
