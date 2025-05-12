package net.civarmymod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.nbt.NbtString;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * NPC UUID를 관리하는 클래스
 * 서버에서 전송된 NPC UUID를 저장하고 관리합니다.
 */
@Environment(EnvType.CLIENT)
public class NPCManager {
    private static final Gson GSON = new GsonBuilder().create();
    private static final boolean DEBUG_MODE = true;
    private static final Logger LOGGER = LogManager.getLogger("CivArmyMod/NPCManager");

    private static NPCManager instance;
    private final Set<UUID> npcUuids = new CopyOnWriteArraySet<>();
    private Consumer<Set<UUID>> npcUpdateCallback;

    /**
     * 싱글톤 인스턴스 가져오기
     */
    public static NPCManager getInstance() {
        if (instance == null) {
            LOGGER.info("[NPCManager 초기화] 싱글톤 인스턴스 생성");
            instance = new NPCManager();
        }
        return instance;
    }

    private NPCManager() {
        LOGGER.info("[NPCManager 생성자] 초기화 시작");
        // 초기화 시 저장된 NPC UUID 로드
        loadNpcUuids();
        LOGGER.info("[NPCManager 생성자] 초기화 완료");
    }

    /**
     * NPC UUID 추가
     * @param uuid 추가할 NPC의 UUID
     * @return 추가 성공 여부 (이미 존재하면 false)
     */
    public boolean addNpcUuid(UUID uuid) {
        if (uuid == null) {
            logWarn("NPC UUID가 null입니다.");
            return false;
        }

        boolean added = npcUuids.add(uuid);
        if (added) {
            logInfo("NPC UUID 추가됨: " + uuid);
            saveNpcUuids(); // 변경사항 저장
            notifyUpdateListeners(); // 리스너에게 알림
        } else {
            logDebug("이미 존재하는 NPC UUID: " + uuid);
        }
        return added;
    }

    /**
     * NPC UUID 추가 (문자열 형식)
     * @param uuidStr UUID 문자열
     * @return 추가 성공 여부
     */
    public boolean addNpcUuid(String uuidStr) {
        try {
            UUID uuid = UUID.fromString(uuidStr);
            logInfo("문자열에서 UUID 변환 시도: " + uuidStr + " -> " + uuid);
            return addNpcUuid(uuid);
        } catch (IllegalArgumentException e) {
            logError("잘못된 UUID 형식: " + uuidStr, e);
            return false;
        }
    }

    /**
     * NPC UUID 제거
     * @param uuid 제거할 NPC의 UUID
     * @return 제거 성공 여부 (존재하지 않으면 false)
     */
    public boolean removeNpcUuid(UUID uuid) {
        if (uuid == null) return false;

        boolean removed = npcUuids.remove(uuid);
        if (removed) {
            logInfo("NPC UUID 제거됨: " + uuid);
            saveNpcUuids(); // 변경사항 저장
            notifyUpdateListeners(); // 리스너에게 알림
        }
        return removed;
    }

    /**
     * 모든 NPC UUID 제거
     */
    public void clearNpcUuids() {
        if (!npcUuids.isEmpty()) {
            npcUuids.clear();
            logInfo("모든 NPC UUID가 제거되었습니다.");
            saveNpcUuids(); // 변경사항 저장
            notifyUpdateListeners(); // 리스너에게 알림
        }
    }

    /**
     * UUID가 NPC인지 확인
     * @param uuid 확인할 UUID
     * @return NPC UUID인 경우 true
     */
    public boolean isNpcUuid(UUID uuid) {
        if (uuid == null) {
            LOGGER.debug("[NPC UUID 확인] null UUID 전달됨");
            return false;
        }
        boolean isNpc = npcUuids.contains(uuid);
        LOGGER.info("[NPC UUID 확인] UUID: " + uuid + ", NPC 여부: " + isNpc);
        return isNpc;
    }

    /**
     * 모든 NPC UUID 가져오기
     * @return NPC UUID 세트 (읽기 전용)
     */
    public Set<UUID> getAllNpcUuids() {
        return new HashSet<>(npcUuids); // 복사본 반환
    }

    /**
     * NPC UUID 개수 가져오기
     * @return NPC UUID 개수
     */
    public int getNpcCount() {
        return npcUuids.size();
    }

    /**
     * NPC UUID 업데이트 콜백 설정
     * @param callback NPC UUID 세트가 변경될 때 호출될 콜백
     */
    public void setNpcUpdateCallback(Consumer<Set<UUID>> callback) {
        this.npcUpdateCallback = callback;
    }

    /**
     * NPC UUID 업데이트 리스너에게 알림
     */
    private void notifyUpdateListeners() {
        LOGGER.debug("[NPC 업데이트] 리스너에게 알림 시작");
        if (npcUpdateCallback != null) {
            LOGGER.debug("[NPC 업데이트] 콜백 호출, NPC 개수: " + npcUuids.size());
            npcUpdateCallback.accept(new HashSet<>(npcUuids));
        } else {
            LOGGER.debug("[NPC 업데이트] 등록된 콜백 없음");
        }
    }

    /**
     * JSON 응답에서 NPC UUID 처리
     * @param jsonResponse 서버로부터 받은 JSON 응답
     * @return 처리된 NPC UUID 개수
     */
    public int processNpcUuidsFromJson(JsonObject jsonResponse) {
        LOGGER.debug("[NPC UUID 처리] JSON 응답 처리 시작");
        
        if (jsonResponse == null) {
            LOGGER.warn("[NPC UUID 처리] 응답이 null");
            return 0;
        }

        int processedCount = 0;
        
        try {
            // NPC UUID 배열 확인
            if (jsonResponse.has("npcUuids")) {
                LOGGER.debug("[NPC UUID 처리] 응답에 npcUuids 필드 발견");
                
                if (jsonResponse.get("npcUuids").isJsonArray()) {
                    JsonArray npcUuidsArray = jsonResponse.getAsJsonArray("npcUuids");
                    LOGGER.info("[NPC UUID 처리] 서버에서 " + npcUuidsArray.size() + "개의 NPC UUID 수신");
                    
                    // 리셋 플래그 확인
                    boolean resetNpcs = false;
                    if (jsonResponse.has("resetNpcs")) {
                        LOGGER.info("[NPC UUID 처리] resetNpcs 필드 발견");
                        
                        if (jsonResponse.get("resetNpcs").isJsonPrimitive()) {
                            resetNpcs = jsonResponse.get("resetNpcs").getAsBoolean();
                            LOGGER.info("[NPC UUID 처리] resetNpcs 값: " + resetNpcs);
                        }
                    }
                    
                    // 리셋이 필요하면 기존 UUID 모두 제거
                    if (resetNpcs) {
                        LOGGER.info("[NPC UUID 처리] resetNpcs=true, 기존 NPC UUID " + npcUuids.size() + "개 초기화");
                        npcUuids.clear();
                    }
                
                    // 새 UUID 추가
                    for (JsonElement element : npcUuidsArray) {
                        if (element.isJsonPrimitive()) {
                            String uuidStr = element.getAsString();
                            LOGGER.info("[NPC UUID 처리] UUID 문자열 처리: " + uuidStr);
                            
                            try {
                                UUID uuid = UUID.fromString(uuidStr);
                                if (npcUuids.add(uuid)) {
                                    processedCount++;
                                    LOGGER.info("[NPC UUID 처리] UUID 추가 성공: " + uuid);
                                } else {
                                    LOGGER.info("[NPC UUID 처리] 이미 존재하는 UUID: " + uuid);
                                }
                            } catch (IllegalArgumentException e) {
                                LOGGER.warn("[NPC UUID 처리] 잘못된 UUID 형식: " + uuidStr);
                            }
                        } else {
                            LOGGER.warn("[NPC UUID 처리] JSON 배열의 요소가 기본형이 아님");
                        }
                    }
                    
                    // 변경사항이 있으면 저장 및 알림
                    if (processedCount > 0 || resetNpcs) {
                        LOGGER.info("[NPC UUID 처리] " + processedCount + "개의 새 UUID 추가됨, 변경사항 저장 및 알림 시작");
                        saveNpcUuids();
                        notifyUpdateListeners();
                    } else {
                        LOGGER.info("[NPC UUID 처리] 변경사항 없음");
                    }
                }
            }
        } catch (Exception e) {
            logError("NPC UUID 처리 중 오류 발생: " + e.getMessage(), e);
        }
        
        return processedCount;
    }

    /**
     * NPC UUID 저장
     */
    public void saveNpcUuids() {
        LOGGER.debug("[NPC UUID 저장] 저장 시작");
        
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.world == null || client.player == null) {
                LOGGER.warn("[NPC UUID 저장] 실패: 클라이언트/월드/플레이어 정보 없음");
                return;
            }
            
            LOGGER.debug("[NPC UUID 저장] 클라이언트/월드/플레이어 정보 확인 완료");

            Path saveDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("npc_data");
            LOGGER.debug("[NPC UUID 저장] 저장 디렉토리: " + saveDir);
            
            if (!saveDir.toFile().exists()) {
                LOGGER.debug("[NPC UUID 저장] 저장 디렉토리가 없음, 생성 시도");
                
                if (!saveDir.toFile().mkdirs()) {
                    LOGGER.error("[NPC UUID 저장] 데이터 저장 폴더 생성 실패: " + saveDir);
                    return;
                }
                LOGGER.debug("[NPC UUID 저장] 데이터 저장 폴더 생성됨: " + saveDir);
            }

            String worldId = client.world.getRegistryKey().getValue().toString().replace(':', '_').replace('/', '_');
            String playerName = client.player.getGameProfile().getName();
            File saveFile = saveDir.resolve(worldId + "_" + playerName + "_npcs.dat").toFile();
            LOGGER.debug("[NPC UUID 저장] 저장 파일 경로: " + saveFile.getAbsolutePath());

            // NBT 형식으로 저장
            LOGGER.debug("[NPC UUID 저장] NBT 데이터 생성 시작");
            NbtCompound root = new NbtCompound();
            NbtList npcList = new NbtList();
            
            int count = 0;
            for (UUID uuid : npcUuids) {
                npcList.add(NbtString.of(uuid.toString()));
                count++;
            }
            
            LOGGER.debug("[NPC UUID 저장] " + count + "개의 UUID를 NBT로 변환");
            root.put("npcUuids", npcList);
            
            LOGGER.debug("[NPC UUID 저장] 파일에 저장 시작");
            NbtIo.writeCompressed(root, saveFile.toPath());
            LOGGER.info("[NPC UUID 저장] " + npcUuids.size() + "개 저장 완료: " + saveFile.getName());
            
        } catch (Exception e) {
            logError("NPC UUID 저장 중 오류 발생: " + e.getMessage(), e);
        }
    }

    /**
     * NPC UUID 로드
     */
    public void loadNpcUuids() {
        LOGGER.debug("[NPC UUID 로드] 로드 시작");
        
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.world == null || client.player == null) {
                LOGGER.debug("[NPC UUID 로드] 보류: 클라이언트/월드/플레이어 정보 없음");
                return;
            }
            
            LOGGER.info("[NPC UUID 로드] 클라이언트/월드/플레이어 정보 확인 완료");

            Path saveDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("npc_data");
            String worldId = client.world.getRegistryKey().getValue().toString().replace(':', '_').replace('/', '_');
            String playerName = client.player.getGameProfile().getName();
            File saveFile = saveDir.resolve(worldId + "_" + playerName + "_npcs.dat").toFile();
            LOGGER.info("[NPC UUID 로드] 로드 파일 경로: " + saveFile.getAbsolutePath());

            if (!saveFile.exists()) {
                LOGGER.info("[NPC UUID 로드] 저장된 파일 없음: " + saveFile.getName());
                return;
            }
            
            LOGGER.info("[NPC UUID 로드] 저장 파일 존재 확인");

            // 기존 데이터 초기화
            LOGGER.info("[NPC UUID 로드] 기존 데이터 초기화");
            npcUuids.clear();

            // NBT 형식으로 로드
            LOGGER.info("[NPC UUID 로드] NBT 파일 읽기 시작");
            NbtCompound root = NbtIo.readCompressed(saveFile.toPath(), NbtSizeTracker.ofUnlimitedBytes());
            
            if (root.contains("npcUuids", NbtList.STRING_TYPE)) {
                LOGGER.info("[NPC UUID 로드] npcUuids 필드 발견");
                NbtList npcList = root.getList("npcUuids", NbtString.STRING_TYPE);
                LOGGER.info("[NPC UUID 로드] " + npcList.size() + "개의 UUID 로드 시작");
                
                int loadedCount = 0;
                for (int i = 0; i < npcList.size(); i++) {
                    String uuidStr = npcList.getString(i);
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        npcUuids.add(uuid);
                        loadedCount++;
                        LOGGER.info("[NPC UUID 로드] UUID 로드: " + uuid);
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("[NPC UUID 로드] 잘못된 UUID 형식: " + uuidStr);
                    }
                }
                
                LOGGER.info("[NPC UUID 로드] " + loadedCount + "개 로드 완료");
                
                // 로드 후 리스너에게 알림
                if (loadedCount > 0) {
                    LOGGER.debug("[NPC UUID 로드] 리스너에게 알림 전송");
                    notifyUpdateListeners();
                }
            } else {
                LOGGER.info("[NPC UUID 로드] npcUuids 필드가 없거나 형식이 잘못됨");
            }
            
        } catch (Exception e) {
            logError("NPC UUID 로드 중 오류 발생: " + e.getMessage(), e);
        }
    }

    // --- 로깅 헬퍼 ---
    private static void logInfo(String message) { LOGGER.info(message); }
    private static void logWarn(String message) { LOGGER.warn(message); }
    private static void logError(String message, Throwable throwable) { 
        if (throwable != null) {
            LOGGER.error(message, throwable);
        } else {
            LOGGER.error(message);
        }
    }
    private static void logError(String message) { logError(message, null); }
    private static void logDebug(String message) { if (DEBUG_MODE) LOGGER.debug(message); }
}
