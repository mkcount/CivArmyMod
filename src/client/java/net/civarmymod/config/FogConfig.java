package net.civarmymod.config;

import net.civarmymod.FogOfWarClient;
import net.fabricmc.loader.api.FabricLoader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 전장의 안개 시스템 설정 관리 클래스
 */
public class FogConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("fog_of_war.json").toFile();
    
    private static ConfigData config;
    
    /**
     * 설정 데이터 클래스
     */
    public static class ConfigData {
        // 웹소켓 엔드포인트 URL
        public String websocketEndpoint = "ws://localhost:8080/api/fog/ws";
        
        // 디버그 모드 (로그 출력)
        public boolean debugMode = false;
    }
    
    /**
     * 설정 로드
     */
    public static void load() {
        if (!CONFIG_FILE.exists()) {
            config = new ConfigData();
            save();
            return;
        }
        
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            config = GSON.fromJson(reader, ConfigData.class);
            
            // 설정값 적용 - 순환 참조 방지를 위해 FogOfWarClient 인스턴스가 있을 때만 호출
            if (FogOfWarClient.getInstance() != null) {
                FogOfWarClient.getInstance().setWebsocketEndpoint(config.websocketEndpoint);
                System.out.println("전장의 안개 설정 로드 완료: 웹소켓 엔드포인트 = " + config.websocketEndpoint);
            } else {
                System.out.println("전장의 안개 설정 로드 완료 (웹소켓 엔드포인트 적용 대기)");
            }
        } catch (IOException e) {
            System.err.println("전장의 안개 설정 로드 실패: " + e.getMessage());
            config = new ConfigData();
        }
    }
    
    /**
     * 설정 저장
     */
    public static void save() {
        try {
            if (!CONFIG_FILE.exists()) {
                CONFIG_FILE.getParentFile().mkdirs();
                CONFIG_FILE.createNewFile();
            }
            
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(config, writer);
            }
            
            System.out.println("전장의 안개 설정 저장 완료");
        } catch (IOException e) {
            System.err.println("전장의 안개 설정 저장 실패: " + e.getMessage());
        }
    }
    
    /**
     * 현재 설정 반환
     */
    public static ConfigData getConfig() {
        if (config == null) {
            load();
        }
        return config;
    }
    
    /**
     * 웹소켓 엔드포인트 설정
     */
    public static void setWebsocketEndpoint(String endpoint) {
        if (config == null) {
            load();
        }
        config.websocketEndpoint = endpoint;
        
        // 순환 참조 방지를 위해 FogOfWarClient 인스턴스가 있을 때만 호출
        if (FogOfWarClient.getInstance() != null) {
            FogOfWarClient.getInstance().setWebsocketEndpoint(endpoint);
        }
        
        save();
    }
}
