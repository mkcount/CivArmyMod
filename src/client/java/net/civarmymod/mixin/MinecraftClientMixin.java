package net.civarmymod.mixin;

import net.civarmymod.FogOfWarClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 마인크래프트 클라이언트 종료 시 안개 데이터를 저장합니다.
 */
@Environment(EnvType.CLIENT)
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    /**
     * 게임 종료 시 안개 데이터 저장
     */
    @Inject(method = "stop", at = @At("HEAD"))
    private void onGameStop(CallbackInfo ci) {
        System.out.println("게임 종료 감지: 안개 데이터 저장 중...");
        FogOfWarClient.getInstance().saveData();
    }
    
    /**
     * 월드 로드 시 데이터 로드 (아직 초기화가 완료되지 않은 경우)
     */
    @Inject(method = "joinWorld", at = @At("RETURN"))
    private void onJoinWorld(CallbackInfo ci) {
        System.out.println("월드 접속 감지: 안개 데이터 로드 중...");
        FogOfWarClient.getInstance().loadData();
    }
}
