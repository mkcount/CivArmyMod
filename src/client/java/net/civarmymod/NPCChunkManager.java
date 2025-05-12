package net.civarmymod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;

/**
 * NPC 주변 청크 상태를 관리하는 클래스
 * NPC 위치를 기반으로 청크 상태를 VISIBLE, FOGGED, HIDDEN으로 설정합니다.
 */
public class NPCChunkManager {
    private static final boolean DEBUG_MODE = true;
    private static boolean LOG_ENABLED = true; // 로그 활성화 변수 추가
    private static final Logger LOGGER = LogManager.getLogger("CivArmyMod/NPCChunkManager");
    private static NPCChunkManager instance;

    // 청크 범위 설정
    private static final int NPC_CHECK_RADIUS = 1; // 3x3 범위 (중심 + 좌우상하 각 1칸)
    private static final int NPC_SCAN_RADIUS = 2; // 5x5 범위 (중심 + 좌우상하 각 2칸)

    // 청크 상태 관리는 FogOfWarClient에서 직접 수행

    // 업데이트 주기 (밀리초)
    public static final long UPDATE_INTERVAL_MS = 2000;

    // 스케줄러
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * 싱글톤 인스턴스 가져오기
     */
    public static NPCChunkManager getInstance() {
        if (instance == null) {
            instance = new NPCChunkManager();
        }
        return instance;
    }

    private NPCChunkManager() {
        // 주기적으로 청크 상태 업데이트
        scheduler.scheduleAtFixedRate(this::updateChunkStates, 0, UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // NPCManager에 리스너 등록 - NPC 추가/제거 시 청크 업데이트 트리거
        try {
            NPCManager npcManager = NPCManager.getInstance();
            if (npcManager != null) {
                npcManager.setNpcUpdateCallback(uuids -> {
                    logInfo("[NPC 변경 감지] " + uuids.size() + "개의 NPC UUID 변경 감지, 청크 업데이트 트리거");
                    updateChunkStates(); // NPC 변경시 청크 업데이트 즉시 실행
                });
                logInfo("[NPC 변경 감지] NPCManager에 청크 업데이트 콜백 등록 완료");
            }
        } catch (Exception e) {
            LOGGER.error("[NPC 변경 감지] NPCManager에 콜백 등록 중 오류", e);
        }

        logInfo("NPC 청크 매니저 초기화 완료. 업데이트 주기: " + UPDATE_INTERVAL_MS + "ms");
    }

    /**
     * 클라이언트 종료 시 정리 작업
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logInfo("NPC 청크 매니저 종료됨");
    }

    /**
     * 청크 상태 업데이트
     * 모든 NPC 주변 청크를 확인하고 상태를 설정합니다.
     */
    public void updateChunkStates() {
        try {
            logDebug("[청크 상태 업데이트] 시작...");

            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.world == null || client.player == null) {
                logDebug("[청크 상태 업데이트] 클라이언트/월드/플레이어 정보가 없어 건너뛰");
                return; // 클라이언트/월드/플레이어 정보가 없으면 업데이트 안함
            }

            FogOfWarClient fogClient = FogOfWarClient.getInstance();
            if (fogClient == null) {
                logDebug("[청크 상태 업데이트] FogOfWarClient가 초기화되지 않아 건너뛰");
                return; // FogOfWarClient가 초기화되지 않았으면 업데이트 안함
            }

            logDebug("[청크 상태 업데이트] 클라이언트 및 FogOfWarClient 확인 완료");

            // 1. NPC 엔티티 목록 가져오기
            List<PlayerEntity> npcEntities = getNpcEntities();
            if (npcEntities.isEmpty()) {
                logDebug("NPC가 없습니다. 청크 상태 업데이트 건너뛰.");
                return;
            }

            logDebug(npcEntities.size() + "개의 NPC 발견. 청크 상태 업데이트 시작...");

            // 2. 모든 청크를 기본적으로 HIDDEN으로 설정할 준비
            Set<ChunkPosition> allChunks = new HashSet<>();
            Set<ChunkPosition> visibleChunks = new HashSet<>();
            Set<ChunkPosition> foggedChunks = new HashSet<>();

            // 3. 각 NPC의 청크 위치 계산
            Map<ChunkPosition, Boolean> npcChunks = new HashMap<>();
            for (PlayerEntity npc : npcEntities) {
                int npcChunkX = (int) Math.floor(npc.getX()) >> 4;
                int npcChunkZ = (int) Math.floor(npc.getZ()) >> 4;
                npcChunks.put(new ChunkPosition(npcChunkX, npcChunkZ), true);
            }

            // 4. 모든 NPC 주변 5x5 청크 스캔
            for (ChunkPosition npcChunk : npcChunks.keySet()) {
                for (int dx = -NPC_SCAN_RADIUS; dx <= NPC_SCAN_RADIUS; dx++) {
                    for (int dz = -NPC_SCAN_RADIUS; dz <= NPC_SCAN_RADIUS; dz++) {
                        ChunkPosition scanPos = new ChunkPosition(npcChunk.x + dx, npcChunk.z + dz);
                        allChunks.add(scanPos);
                    }
                }
            }

            // 5. 각 청크에 대해 3x3 범위 내에 NPC가 있는지 확인
            for (ChunkPosition chunk : allChunks) {
                boolean hasNearbyNpc = false;

                // 청크 주변 3x3 범위 확인
                for (int dx = -NPC_CHECK_RADIUS; dx <= NPC_CHECK_RADIUS; dx++) {
                    for (int dz = -NPC_CHECK_RADIUS; dz <= NPC_CHECK_RADIUS; dz++) {
                        ChunkPosition checkPos = new ChunkPosition(chunk.x + dx, chunk.z + dz);
                        if (npcChunks.containsKey(checkPos)) {
                            hasNearbyNpc = true;
                            break;
                        }
                    }
                    if (hasNearbyNpc)
                        break;
                }

                // 상태 결정
                if (hasNearbyNpc) {
                    // 주변에 NPC가 있으면 VISIBLE
                    if (!fogClient.isVisibleChunk(chunk.x, chunk.z)) {
                        // 현재 VISIBLE 상태가 아닌 경우에만 추가
                        visibleChunks.add(chunk);
                    }
                } else if (fogClient.isVisibleChunk(chunk.x, chunk.z)) {
                    // 원래 VISIBLE이었는데 지금은 주변에 NPC가 없으면 FOGGED
                    if (!fogClient.isFoggedChunk(chunk.x, chunk.z)) {
                        // 현재 FOGGED 상태가 아닌 경우에만 추가
                        foggedChunks.add(chunk);
                    }
                }
                // 그 외의 청크는 HIDDEN (여기서는 별도의 작업이 필요 없음)
            }

            // 6. 청크 상태 업데이트
            updateFogOfWarChunkStates(visibleChunks, foggedChunks, allChunks);

            logDebug("청크 상태 업데이트 완료: " + visibleChunks.size() + "개 VISIBLE, " +
                    foggedChunks.size() + "개 FOGGED, " +
                    (allChunks.size() - visibleChunks.size() - foggedChunks.size()) + "개 HIDDEN");

        } catch (Exception e) {
            logError("청크 상태 업데이트 중 오류 발생: " + e.getMessage(), e);
        }
    }

    /**
     * FogOfWarClient의 청크 상태 업데이트
     */
    private void updateFogOfWarChunkStates(Set<ChunkPosition> visibleChunks, Set<ChunkPosition> foggedChunks,
            Set<ChunkPosition> allChunks) {
        logInfo("[청크 상태 설정] 청크 상태 업데이트 시작");
        logInfo("[청크 상태 통계] VISIBLE: " + visibleChunks.size() + "개, FOGGED: " +
                foggedChunks.size() + "개, 총 청크: " + allChunks.size() + "개");

        FogOfWarClient fogClient = FogOfWarClient.getInstance();
        if (fogClient == null) {
            logError("[청크 상태 설정] FogOfWarClient가 null");
            return;
        }

        // 변경 사항 추적
        boolean hasChanges = false;
        logDebug("[청크 상태 설정] 청크 상태 변경 추적 시작");

        // 변경된 청크만 추적하는 집합
        Set<ChunkPosition> changedChunks = new HashSet<>();

        // VISIBLE 청크 설정 - 이미 visibleChunks에는 현재 VISIBLE이 아닌 청크만 포함되어 있음
        for (ChunkPosition pos : visibleChunks) {
            logInfo("[청크 상태 설정] 청크 (" + pos.x + ", " + pos.z + ")를 VISIBLE로 설정 시도");
            setChunkState(fogClient, pos.x, pos.z, FogOfWarClient.ChunkState.VISIBLE);
            changedChunks.add(pos);
            hasChanges = true;
        }

        // FOGGED 청크 설정 - 이미 foggedChunks에는 현재 FOGGED가 아닌 청크만 포함되어 있음
        for (ChunkPosition pos : foggedChunks) {
            logInfo("[청크 상태 설정] 청크 (" + pos.x + ", " + pos.z + ")를 FOGGED로 설정 시도");
            setChunkState(fogClient, pos.x, pos.z, FogOfWarClient.ChunkState.FOGGED);
            changedChunks.add(pos);
            hasChanges = true;
        }

        // 변경 사항이 있으면 렌더러 리로드 및 웹소켓 업데이트
        if (hasChanges) {
            // 이미 변경된 청크 좌표를 수집했으므로 추가 수집 작업이 필요 없음

            // 청크 단위로 렌더링 리프레시 요청
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.world != null) {
                    logInfo("청크 단위 리렌더링 요청 (청크 수: " + changedChunks.size() + ")");

                    // Sodium 지원 확인
                    boolean isSodiumLoaded = false;
                    try {
                        Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
                        isSodiumLoaded = true;
                        logInfo("Sodium 모드가 감지되었습니다. Sodium 호환 청크 리로드 사용");
                    } catch (ClassNotFoundException e) {
                        logInfo("Sodium 모드가 감지되지 않았습니다. 기본 청크 리로드 사용");
                    }

                    for (ChunkPosition pos : changedChunks) {
                        final int x = pos.x;
                        final int z = pos.z;

                        if (isSodiumLoaded) {
                            // MinecraftClient.execute를 사용하여 메인 스레드에서 실행
                            MinecraftClient.getInstance().execute(() -> {
                                ChunkReloadManager.requestChunkReload(x, z);
                                logInfo("[청크 execute]");
                            });
                        } 
                    }

                    if (isSodiumLoaded && !changedChunks.isEmpty()) {
                        logInfo("Sodium 호환 청크 리로드 요청 완료 (" + changedChunks.size() + "개 청크)");
                    }

                } else {
                    if (client == null) {
                        logError("청크 리로드 실패: MinecraftClient가 null입니다.");
                    } else { // client.world is null
                        logError("청크 리로드 실패: client.world가 null입니다.");
                    }
                }
            } catch (Exception e) {
                logError("청크 리프레시 요청 중 오류 발생: " + e.getMessage(), e);
            }
        } else {
            logInfo("변경사항 없음");
        }
    }

    /**
     * 청크 상태 설정 헬퍼 메소드
     */
    private void setChunkState(FogOfWarClient fogClient, int x, int z, FogOfWarClient.ChunkState state) {
        try {
            // FogOfWarClient에 추가한 setChunkState 메소드 사용
            fogClient.setChunkState(x, z, state);
        } catch (Exception e) {
            logError("청크 상태 설정 중 오류 발생: " + e.getMessage(), e);
        }

    }

    /**
     * NPC 엔티티 목록 가져오기
     */
    private List<PlayerEntity> getNpcEntities() {
        LOGGER.debug("[NPC 엔티티 검색] 월드에서 NPC 엔티티 검색 시작");

        List<PlayerEntity> npcEntities = new ArrayList<>();

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) {
            LOGGER.debug("[NPC 엔티티 검색] 클라이언트 또는 월드가 null");
            return npcEntities;
        }

        NPCManager npcManager = NPCManager.getInstance();
        if (npcManager == null) {
            LOGGER.debug("[NPC 엔티티 검색] NPCManager가 초기화되지 않음");
            return npcEntities;
        }

        // NPCManager에서 관리하는 UUID 목록 가져오기
        Set<UUID> npcUuids = npcManager.getAllNpcUuids();
        LOGGER.debug("[NPC 엔티티 검색] NPCManager에서 " + npcUuids.size() + "개의 NPC UUID 가져옴");

        // 월드에서 모든 플레이어 엔티티 검색
        int totalPlayers = 0;
        for (Entity entity : client.world.getEntities()) {
            if (entity instanceof PlayerEntity) {
                totalPlayers++;
                PlayerEntity player = (PlayerEntity) entity;

                // 플레이어가 NPC인지 확인
                if (player.getUuid() != null && npcUuids.contains(player.getUuid())) {
                    npcEntities.add(player);
                    LOGGER.debug("[NPC 엔티티 검색] NPC 발견: " + player.getName().getString() +
                            " (UUID: " + player.getUuid() + "), 위치: (" +
                            (int) player.getX() + ", " + (int) player.getZ() + "), 청크: (" +
                            ((int) player.getX() >> 4) + ", " + ((int) player.getZ() >> 4) + ")");
                }
            }
        }

        logInfo("[NPC 엔티티 검색] 전체 플레이어 " + totalPlayers + "명 중 " +
                npcEntities.size() + "개의 NPC 발견");
        return npcEntities;
    }

    /**
     * 청크 위치 클래스 (FogOfWarClient의 ChunkPosition과 호환)
     */
    public static class ChunkPosition {
        public final int x;
        public final int z;

        public ChunkPosition(int x, int z) {
            this.x = x;
            this.z = z;
        }

        public ChunkPosition(ChunkPos pos) {
            this.x = pos.x;
            this.z = pos.z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            ChunkPosition that = (ChunkPosition) o;
            return x == that.x && z == that.z;
        }

        @Override
        public int hashCode() {
            return 31 * x + z;
        }

        @Override
        public String toString() {
            return "(" + x + ", " + z + ")";
        }
    }

    /**
     * 로그 활성화 상태 설정
     * 
     * @param enabled true면 로그 활성화, false면 비활성화
     */
    public static void setLogEnabled(boolean enabled) {
        LOG_ENABLED = enabled;
        LOGGER.info("NPCChunkManager 로그 " + (enabled ? "활성화" : "비활성화") + " 됨");
    }

    /**
     * 현재 로그 활성화 상태 가져오기
     * 
     * @return 로그 활성화 상태
     */
    public static boolean isLogEnabled() {
        return LOG_ENABLED;
    }

    // --- 로깅 헬퍼 ---
    private void logInfo(String message) {
        if (LOG_ENABLED || message.contains("[오류]") || message.contains("초기화")) {
            LOGGER.info(message);
        }
    }

    private void logWarn(String message) {
        // 경고는 항상 출력
        LOGGER.warn(message);
    }

    private void logError(String message, Throwable throwable) {
        // 오류는 항상 출력
        if (throwable != null) {
            LOGGER.error(message, throwable);
        } else {
            LOGGER.error(message);
        }
    }

    private void logError(String message) {
        logError(message, null);
    }

    private void logDebug(String message) {
        if (DEBUG_MODE && LOG_ENABLED) {
            LOGGER.debug(message);
        }
    }
}
