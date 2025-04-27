package net.civarmymod.config;

import net.civarmymod.FogOfWarClient;
import net.fabricmc.loader.api.FabricLoader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException; // Json 파싱 예외 처리용
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files; // Files 클래스 사용

/**
 * 전장의 안개 시스템 설정 관리 클래스
 */
public class FogConfig {
    // GSON 인스턴스 (직렬화 시 null 필드 포함하지 않도록 설정 가능)
    private static final Gson GSON = new GsonBuilder()
            // .serializeNulls() // null 값도 저장하고 싶다면 주석 해제
            .setPrettyPrinting().create();
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("fog_of_war.json").toFile();

    // 기본 설정값 정의
    private static final String DEFAULT_WEBSOCKET_ENDPOINT = "ws://localhost:8080/api/fog/ws";
    private static final String DEFAULT_FOG_BLOCK_ID = "minecraft:gray_concrete"; // 기본값: 회색 콘크리트
    private static final boolean DEFAULT_DEBUG_MODE = false;

    private static ConfigData config; // 로드된 설정 데이터 저장

    /**
     * 설정 데이터 클래스 (POJO)
     */
    public static class ConfigData {
        // 웹소켓 엔드포인트 URL
        public String websocketEndpoint = DEFAULT_WEBSOCKET_ENDPOINT;

        // 디버그 모드 (로그 출력)
        public boolean debugMode = DEFAULT_DEBUG_MODE;

        // 기본 안개 블록 ID 추가
        public String defaultFogBlockId = DEFAULT_FOG_BLOCK_ID;

        // 생성자 (기본값 설정) - 파일 없을 때 사용됨
        public ConfigData() {}
    }

    /**
     * 설정 로드 (모드 초기화 시 호출)
     */
    public static void load() {
        System.out.println("[FogConfig] 설정 로드 시도: " + CONFIG_FILE.getAbsolutePath());
        if (!CONFIG_FILE.exists()) {
             System.out.println("[FogConfig] 설정 파일이 존재하지 않아 기본값으로 생성합니다.");
            config = new ConfigData(); // 기본값으로 객체 생성
            save(); // 기본 설정 파일 저장
            applyConfig(); // 기본값 적용 (필요 시)
            return;
        }

        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            config = GSON.fromJson(reader, ConfigData.class);
            System.out.println("[FogConfig] 설정 파일 로드 완료.");

            // 로드 후 null 값 검사 및 기본값 채우기 (선택적이지만 권장)
            if (config == null) { // 파일이 비어있거나 형식이 잘못된 경우
                System.err.println("[FogConfig] 설정 파일 내용이 비어있거나 잘못되어 기본 설정을 사용합니다.");
                config = new ConfigData();
                // 기존 파일 백업 후 새로 저장하는 로직 추가 가능
                // backupAndSave();
            } else {
                boolean needsSave = false; // 기본값이 채워져서 저장이 필요한지 여부
                if (config.websocketEndpoint == null) {
                    config.websocketEndpoint = DEFAULT_WEBSOCKET_ENDPOINT;
                    needsSave = true;
                }
                // debugMode는 boolean이라 null 체크 불필요 (기본 false)
                if (config.defaultFogBlockId == null) {
                    config.defaultFogBlockId = DEFAULT_FOG_BLOCK_ID;
                    needsSave = true;
                }
                // 누락된 필드가 있었다면 파일 다시 저장
                if (needsSave) {
                     System.out.println("[FogConfig] 설정 파일에 누락된 필드가 있어 기본값으로 채우고 다시 저장합니다.");
                     save();
                }
            }

            // 설정값 적용
            applyConfig();

        } catch (IOException e) {
            System.err.println("[FogConfig] 설정 파일 읽기 실패: " + e.getMessage());
            config = new ConfigData(); // 읽기 실패 시 기본값 사용
            applyConfig();
        } catch (JsonSyntaxException e) {
             System.err.println("[FogConfig] 설정 파일 JSON 구문 오류: " + e.getMessage());
             System.err.println("  기본 설정을 사용합니다. 설정 파일을 확인해주세요: " + CONFIG_FILE.getAbsolutePath());
             config = new ConfigData(); // JSON 오류 시 기본값 사용
             // 오류난 파일 백업 로직 추가 가능
             // backupConfigFile();
             applyConfig();
        } catch (Exception e) { // 기타 예외 처리
             System.err.println("[FogConfig] 설정 로드 중 알 수 없는 오류: " + e.getMessage());
             e.printStackTrace();
             config = new ConfigData();
             applyConfig();
        }
    }

    /**
     * 로드된 설정을 FogOfWarClient 등에 적용하는 헬퍼 메서드
     */
    private static void applyConfig() {
        if (config == null) {
             System.err.println("[FogConfig] 설정 객체가 null이어서 적용할 수 없습니다.");
             return;
        }

        // FogOfWarClient 인스턴스 존재 여부 확인 후 적용 (순환 참조 방지)
        FogOfWarClient clientInstance = FogOfWarClient.getInstance();
        if (clientInstance != null) {
            System.out.println("[FogConfig] FogOfWarClient 인스턴스에 설정 적용 중...");
            // 웹소켓 엔드포인트 적용
            clientInstance.setWebsocketEndpoint(config.websocketEndpoint);
            System.out.println("  - 웹소켓 엔드포인트: " + config.websocketEndpoint);

            // 기본 안개 블록 적용
            Identifier id = clientInstance.safeCreateIdentifier(config.defaultFogBlockId); // FogOfWarClient의 메서드 재사용
            if (id != null) {
                Block block = Registries.BLOCK.get(id);
                if (block != Blocks.AIR) { // AIR 블록은 유효하지 않음
                    clientInstance.setDefaultFogBlock(block.getDefaultState());
                    System.out.println("  - 기본 안개 블록: " + id);
                } else {
                     System.err.println("[FogConfig] 설정된 기본 안개 블록 ID가 잘못되었습니다(AIR): " + config.defaultFogBlockId + ". 기존 기본 블록 유지.");
                }
            } else {
                 System.err.println("[FogConfig] 설정된 기본 안개 블록 ID 형식이 잘못되었습니다: " + config.defaultFogBlockId + ". 기존 기본 블록 유지.");
            }

            // 디버그 모드 적용 (FogOfWarClient.DEBUG_MODE가 static final이 아니라면)
            // clientInstance.setDebugMode(config.debugMode); // 예시
            System.out.println("  - 디버그 모드: " + config.debugMode);

        } else {
            // 인스턴스가 아직 없을 때 (초기화 중)
             System.out.println("[FogConfig] FogOfWarClient 인스턴스가 아직 준비되지 않아 설정 적용을 보류합니다.");
             // 초기화 시 FogOfWarClient 생성자 또는 onInitializeClient에서 getConfig()을 다시 호출하여 적용해야 함
        }
    }

    /**
     * 설정 저장
     */
    public static void save() {
        if (config == null) {
             System.err.println("[FogConfig] 저장할 설정 데이터가 없습니다 (config is null).");
             return;
        }

        try {
            // 설정 파일 디렉토리 확인 및 생성
            File parentDir = CONFIG_FILE.getParentFile();
            if (!parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    System.err.println("[FogConfig] 설정 디렉토리 생성 실패: " + parentDir.getAbsolutePath());
                    return;
                }
                 System.out.println("[FogConfig] 설정 디렉토리 생성: " + parentDir.getAbsolutePath());
            }

            // 파일 쓰기 (기존 파일 덮어쓰기)
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(config, writer);
                 System.out.println("[FogConfig] 설정 저장 완료: " + CONFIG_FILE.getAbsolutePath());
            }

        } catch (IOException e) {
            System.err.println("[FogConfig] 설정 파일 쓰기 실패: " + e.getMessage());
        } catch (Exception e) { // Json 쓰기 오류 등
             System.err.println("[FogConfig] 설정 저장 중 오류: " + e.getMessage());
             e.printStackTrace();
        }
    }

    /**
     * 현재 설정 반환 (없으면 로드 시도)
     * 주의: 이 메서드 호출 시 파일 I/O가 발생할 수 있음
     */
    public static ConfigData getConfig() {
        // config가 null이면 로드 시도 (동기화 문제 고려 필요 시 synchronized 블록 사용)
        if (config == null) {
             System.out.println("[FogConfig] getConfig() 호출 시 설정이 로드되지 않아 로드를 시도합니다.");
            load(); // load() 내부에서 config 객체 할당
        }
        // load() 실패 시 config가 여전히 null일 수 있으므로 방어 코드 추가
        if (config == null) {
             System.err.println("[FogConfig] 설정 로드 실패 후 getConfig() 호출됨. 임시 기본 설정을 반환합니다.");
             return new ConfigData(); // 임시 기본값 반환
        }
        return config;
    }

    // --- 설정 변경 및 저장 메서드 (필요 시 추가) ---

    /**
     * 웹소켓 엔드포인트 설정 및 저장
     */
    public static void setWebsocketEndpoint(String endpoint) {
        getConfig(); // config 객체 로드 보장
        if (config != null && endpoint != null && !endpoint.equals(config.websocketEndpoint)) {
             System.out.println("[FogConfig] 웹소켓 엔드포인트 변경 시도: " + endpoint);
            config.websocketEndpoint = endpoint;
            applyConfig(); // 변경된 설정 즉시 적용
            save(); // 변경 사항 파일에 저장
        }
    }

    /**
     * 기본 안개 블록 ID 설정 및 저장
     */
    public static void setDefaultFogBlockId(String blockId) {
        getConfig(); // config 객체 로드 보장
        if (config != null && blockId != null && !blockId.equals(config.defaultFogBlockId)) {
             System.out.println("[FogConfig] 기본 안개 블록 ID 변경 시도: " + blockId);
             // 간단한 유효성 검사 (Identifier 형식)
             try {
                  Identifier.of(blockId.contains(":") ? blockId : "minecraft:" + blockId); // 형식 검사
                  config.defaultFogBlockId = blockId;
                  applyConfig(); // 변경된 설정 즉시 적용
                  save(); // 변경 사항 파일에 저장
             } catch (Exception e) {
                  System.err.println("[FogConfig] 유효하지 않은 블록 ID 형식으로 변경할 수 없습니다: " + blockId);
             }
        }
    }

    /**
     * 디버그 모드 설정 및 저장
     */
    public static void setDebugMode(boolean enabled) {
        getConfig(); // config 객체 로드 보장
        if (config != null && config.debugMode != enabled) {
            System.out.println("[FogConfig] 디버그 모드 변경: " + enabled);
            config.debugMode = enabled;
            applyConfig(); // 변경된 설정 즉시 적용 (FogOfWarClient.DEBUG_MODE가 static final이 아니어야 함)
            save(); // 변경 사항 파일에 저장
        }
    }

    // --- 유틸리티 (백업 등) ---
    private static void backupConfigFile() {
        if (CONFIG_FILE.exists()) {
            File backupFile = new File(CONFIG_FILE.getParentFile(), CONFIG_FILE.getName() + ".bak");
            try {
                 Files.copy(CONFIG_FILE.toPath(), backupFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                 System.out.println("[FogConfig] 기존 설정 파일을 백업했습니다: " + backupFile.getName());
            } catch (IOException e) {
                 System.err.println("[FogConfig] 설정 파일 백업 실패: " + e.getMessage());
            }
        }
    }
}