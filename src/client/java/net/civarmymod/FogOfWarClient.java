package net.civarmymod;

import net.civarmymod.config.FogConfig;
import net.civarmymod.network.FogAPIClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents; // <- 수정된 이벤트
import net.fabricmc.fabric.api.networking.v1.PacketSender; // <- onWorldJoin 시그니처용
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler; // <- onWorldJoin/Leave 시그니처용
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 전장의 안개 시스템 클라이언트 구현
 * 웹소켓을 통해 서버 플러그인과 통신하여 안개 상태를 관리합니다.
 * 초기화 로직을 클라이언트 시작 시점과 월드 접속 시점으로 분리했습니다.
 * 디버그 로그 추가됨.
 */
@Environment(EnvType.CLIENT)
public class FogOfWarClient implements ClientModInitializer {
    // --- 싱글톤 및 상태 플래그 ---
    private static FogOfWarClient instance;
    private boolean initialized = false; // 월드 관련 초기화 완료 여부
    private boolean dataLoaded = false; // 현재 월드 데이터 로드 완료 여부
    // 디버그 플래그 (설정에서 제어 가능하도록 확장 가능)
    public static final boolean DEBUG_MODE = true;

    /**
     * 싱글톤 인스턴스 가져오기
     */
    public static FogOfWarClient getInstance() {
        return instance;
    }

    // --- 데이터 저장 구조 ---
    private Map<ChunkPosition, NbtCompound> chunkSnapshots = new ConcurrentHashMap<>();
    public enum ChunkState { VISIBLE, FOGGED, HIDDEN }
    private final Map<ChunkPosition, ChunkState> chunkStates = new ConcurrentHashMap<>();
    private final Map<ChunkPosition, BlockState> fogBlocks = new ConcurrentHashMap<>();
    private BlockState defaultFogBlock = Blocks.GRAY_CONCRETE.getDefaultState();

    // --- 유틸리티 및 통신 ---
    private FogAPIClient apiClient;

    // --- 초기화 ---
    @Override
    public void onInitializeClient() {
        try {
            instance = this;
            logInfo("전장의 안개 모드 초기화 시작...");

            // 설정 로드 시도
            try {
                FogConfig.load();
                // 설정 기반 초기화 (예: 기본 안개 블록)
                 if (FogConfig.getConfig() != null && FogConfig.getConfig().defaultFogBlockId != null) {
                     Identifier id = safeCreateIdentifier(FogConfig.getConfig().defaultFogBlockId);
                     if (id != null) {
                         Block block = Registries.BLOCK.get(id);
                         if (block != Blocks.AIR) {
                             this.defaultFogBlock = block.getDefaultState();
                             logInfo("설정에서 기본 안개 블록 로드: " + id);
                         } else {
                              logWarn("설정된 기본 안개 블록 ID가 잘못되었습니다(AIR): " + FogConfig.getConfig().defaultFogBlockId);
                         }
                     } else {
                          logWarn("설정된 기본 안개 블록 ID 형식이 잘못되었습니다: " + FogConfig.getConfig().defaultFogBlockId);
                     }
                 }
                logInfo("설정 로드 완료.");
            } catch (Exception e) {
                logError("설정 로드 실패: " + e.getMessage(), e);
            }

            // API 클라이언트 초기화
            try {
                apiClient = new FogAPIClient();
                apiClient.setDataConsumer(this::updateFromApiResponse);
                if (FogConfig.getConfig() != null) {
                    apiClient.setWebsocketEndpoint(FogConfig.getConfig().websocketEndpoint);
                    logInfo("API 클라이언트에 웹소켓 엔드포인트 설정: " + FogConfig.getConfig().websocketEndpoint);
                } else {
                     logWarn("설정을 찾을 수 없어 기본 웹소켓 엔드포인트를 사용합니다.");
                }
            } catch (Exception e) {
                logError("API 클라이언트 초기화 실패: " + e.getMessage(), e);
            }

            // 이벤트 등록
            try {
                ClientPlayConnectionEvents.JOIN.register(this::onWorldJoin);
                ClientPlayConnectionEvents.DISCONNECT.register(this::onWorldLeave);
                ClientLifecycleEvents.CLIENT_STOPPING.register(this::onClientStopping);
                logInfo("이벤트 리스너 등록 완료.");
            } catch (Exception e) {
                logError("이벤트 리스너 등록 실패: " + e.getMessage(), e);
            }

            logInfo("전장의 안개 모드가 초기화되었습니다. (월드 접속 대기 중)");

        } catch (Exception e) {
            logError("전장의 안개 모드 초기화 중 심각한 오류 발생: " + e.getMessage(), e);
        }
    }

    // --- 이벤트 핸들러 ---

    /** 월드 접속 시 호출될 메서드 */
    private void onWorldJoin(ClientPlayNetworkHandler handler, PacketSender sender, MinecraftClient client) {
        logInfo("월드 접속 감지: 안개 데이터 로드 및 연결 시도...");
        try {
            clearFogData(); // 이전 데이터 정리
            loadData(); // 데이터 로드
            dataLoaded = true;
            initialized = true; // 초기화 완료 플래그 설정

            // 웹소켓 연결 시도
            if (apiClient != null && client.player != null) {
                logDebug("Attempting WebSocket connection on world join.");
                apiClient.connectWebSocket(); // 연결 시도
            } else {
                logWarn("플레이어 정보 로드 전 또는 API 클라이언트 문제로 웹소켓 연결 보류.");
            }

            safeReloadWorldRenderer(); // 렌더러 리로드
            logInfo("월드 데이터 로드 및 연결 시도 완료.");

        } catch (Exception e) {
            logError("월드 접속 처리 중 오류: " + e.getMessage(), e);
        }
    }

    /** 월드 떠날 시 호출될 메서드 */
    private void onWorldLeave(ClientPlayNetworkHandler handler, MinecraftClient client) {
        logInfo("월드 떠남 감지: 안개 데이터 저장 및 상태 초기화...");
        try {
            if (dataLoaded) saveData(); // 데이터 저장

            // 상태 초기화
            initialized = false;
            dataLoaded = false;
            clearFogData(); // 내부 데이터 정리

            // 웹소켓 연결 해제
            if (apiClient != null && apiClient.isConnected()) {
                logDebug("Disconnecting WebSocket on world leave.");
                apiClient.disconnectWebSocket();
            }
            logInfo("월드 데이터 저장 및 상태 초기화 완료.");
        } catch (Exception e) {
            logError("월드 떠남 처리 중 오류: " + e.getMessage(), e);
        }
    }

    /** 클라이언트 종료 시 호출될 메서드 */
    private void onClientStopping(MinecraftClient client) {
        logInfo("클라이언트 종료 감지: 최종 데이터 저장 및 정리...");
        try {
            // 월드 접속 중이면 저장
            if (dataLoaded && client != null && client.world != null && client.player != null) {
                 logInfo("종료 전 최종 데이터 저장 시도...");
                saveData();
            }

            // 웹소켓 연결 해제
            if (apiClient != null && apiClient.isConnected()) {
                logDebug("Disconnecting WebSocket on client stopping.");
                apiClient.disconnectWebSocket();
            }
            logInfo("클라이언트 종료 처리 완료.");
        } catch (Exception e) {
            logError("클라이언트 종료 처리 중 오류: " + e.getMessage(), e);
        }
    }

    // --- 핵심 로직 메서드 ---

    /** API 응답 처리 업데이트 */
    public void updateFromApiResponse(JsonObject jsonResponse) {
        MinecraftClient.getInstance().execute(() -> { // 메인 스레드에서 실행 보장
            logDebug("Received API response, processing on main thread.");
            try {
                if (!initialized) {
                    logWarn("API 응답 수신 무시: 아직 월드 초기화 안됨.");
                    return;
                }

                if (!jsonResponse.has("foggedChunks")) {
                     logDebug("API response does not contain 'foggedChunks' array.");
                     // resetState만 있는 경우 처리?
                     if (jsonResponse.has("resetState") && jsonResponse.get("resetState").getAsBoolean()) {
                         handleResetState(new HashSet<>()); // 빈 세트로 초기화
                     }
                     return; // foggedChunks 없으면 종료
                }

                JsonArray foggedChunksArray = jsonResponse.getAsJsonArray("foggedChunks");
                Set<ChunkPosition> updatedChunks = new HashSet<>();
                int updateCount = foggedChunksArray.size();
                logInfo("API 응답 수신: " + updateCount + "개 청크 데이터 처리 시작...");
                int processedCount = 0;

                for (JsonElement element : foggedChunksArray) {
                    if (!element.isJsonObject()) {
                        logWarn("Invalid element in foggedChunks array (not a JSON object): " + element);
                        continue;
                    }
                    JsonObject chunkData = element.getAsJsonObject();
                    if (!chunkData.has("x") || !chunkData.has("z")) {
                         logWarn("Chunk data missing x or z coordinate: " + chunkData);
                         continue;
                    }

                    int x = chunkData.get("x").getAsInt();
                    int z = chunkData.get("z").getAsInt();
                    ChunkPosition chunkPos = new ChunkPosition(x, z);
                    updatedChunks.add(chunkPos);
                    processedCount++;
                    logDebug("Processing chunk (" + x + ", " + z + ")");

                    // 1. 청크 상태 설정
                    ChunkState state = ChunkState.HIDDEN; // API 명세에 따라 기본값 조정 가능
                    if (chunkData.has("state") && chunkData.get("state").isJsonPrimitive()) {
                        String stateStr = chunkData.get("state").getAsString().toUpperCase();
                        try {
                            state = ChunkState.valueOf(stateStr);
                             logDebug("  State set to: " + state);
                        } catch (IllegalArgumentException e) {
                            logWarn("  잘못된 청크 상태 값: " + stateStr + " -> 기본값(" + state + ")으로 처리");
                        }
                    } else {
                         logDebug("  No 'state' field found or invalid type, using default: " + state);
                    }
                    chunkStates.put(chunkPos, state);

                    // 2. 안개 블록 설정
                    BlockState fogBlock = defaultFogBlock;
                    if (chunkData.has("fogBlock") && chunkData.get("fogBlock").isJsonPrimitive()) {
                        String blockId = chunkData.get("fogBlock").getAsString();
                        Identifier blockIdentifier = safeCreateIdentifier(blockId);
                        if (blockIdentifier != null) {
                            Block block = Registries.BLOCK.get(blockIdentifier);
                            if (block != Blocks.AIR) {
                                fogBlock = block.getDefaultState();
                                logDebug("  Fog block set to: " + blockId);
                            } else {
                                logWarn("  잘못된 블록 ID(AIR): " + blockId + " -> 기본 안개 블록 사용");
                            }
                        } else {
                            logWarn("  잘못된 블록 ID 형식: " + blockId + " -> 기본 안개 블록 사용");
                        }
                    } else {
                         logDebug("  No 'fogBlock' field, using default: " + Registries.BLOCK.getId(defaultFogBlock.getBlock()));
                    }
                    // 상태가 VISIBLE일 때는 fogBlocks 맵에 저장할 필요 없음 (메모리 절약)
                    if (state != ChunkState.VISIBLE) {
                         fogBlocks.put(chunkPos, fogBlock);
                    } else {
                         fogBlocks.remove(chunkPos); // VISIBLE이면 커스텀 블록 정보 제거
                    }


                    // 3. 스냅샷 데이터 저장/제거
                    if (state == ChunkState.FOGGED && chunkData.has("snapshot") && chunkData.get("snapshot").isJsonPrimitive()) {
                        String snapshotBase64 = chunkData.get("snapshot").getAsString();
                        logDebug("  Attempting to decode snapshot for FOGGED chunk...");
                        NbtCompound snapshot = FogAPIClient.decodeSnapshot(snapshotBase64);
                        if (snapshot != null) {
                            chunkSnapshots.put(chunkPos, snapshot);
                            logDebug("  Snapshot decoded and stored.");
                        } else {
                            logError("  청크 (" + x + ", " + z + ") 스냅샷 디코딩 실패!");
                            // 오류 시 FOGGED 상태 유지 or 다른 상태로 변경?
                            // chunkStates.put(chunkPos, ChunkState.VISIBLE);
                        }
                    } else {
                        // FOGGED 상태가 아니거나 스냅샷 데이터가 없으면 제거
                        if(chunkSnapshots.remove(chunkPos) != null) {
                             logDebug("  Removed snapshot data for non-FOGGED chunk.");
                        }
                    }
                } // End of chunk processing loop

                logInfo("청크 데이터 처리 완료: " + processedCount + "/" + updateCount);

                // 4. 리셋 상태 처리
                if (jsonResponse.has("resetState") && jsonResponse.get("resetState").getAsBoolean()) {
                    handleResetState(updatedChunks);
                }

                // 5. 월드 렌더러 리로드
                safeReloadWorldRenderer();

            } catch (Exception e) {
                logError("API 응답 처리 중 오류: " + e.getMessage(), e);
            }
        }); // End of MinecraftClient.getInstance().execute
    }

    /** resetState가 true일 때 호출되는 헬퍼 메서드 */
    private void handleResetState(Set<ChunkPosition> updatedChunks) {
        logInfo("'resetState' 요청 처리: 서버 응답에 없는 청크는 VISIBLE로 초기화합니다.");
        Set<ChunkPosition> toRemove = new HashSet<>();
        // chunkStates에만 있는 키를 찾음 (VISIBLE이 아닌 상태로 관리되던 청크)
        chunkStates.keySet().forEach(pos -> {
            if (!updatedChunks.contains(pos)) {
                toRemove.add(pos);
            }
        });
        // fogBlocks에만 있는 키를 찾음 (커스텀 안개 블록이 설정되었던 청크)
         fogBlocks.keySet().forEach(pos -> {
             if (!updatedChunks.contains(pos)) {
                 toRemove.add(pos); // 중복될 수 있지만 Set이 처리
             }
         });
         // chunkSnapshots에만 있는 키를 찾음 (스냅샷이 저장되었던 청크)
         chunkSnapshots.keySet().forEach(pos -> {
             if (!updatedChunks.contains(pos)) {
                 toRemove.add(pos);
             }
         });


        if (!toRemove.isEmpty()) {
             logInfo("  " + toRemove.size() + "개의 청크 상태를 VISIBLE로 초기화합니다: " + toRemove);
            for (ChunkPosition pos : toRemove) {
                chunkStates.remove(pos); // VISIBLE 상태는 맵에서 제거
                fogBlocks.remove(pos);
                chunkSnapshots.remove(pos);
            }
        } else {
             logInfo("  초기화할 기존 청크 데이터가 없습니다.");
        }
    }


    /** 게임 종료 시 안개 상태 및 스냅샷 저장 */
    public void saveData() {
        logDebug("saveData() called.");
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.world == null || client.player == null) {
                logWarn("데이터 저장 시점 오류: 클라이언트/월드/플레이어 정보 없음.");
                return;
            }

            Path saveDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("fog_data");
            if (!saveDir.toFile().exists()) {
                if (!saveDir.toFile().mkdirs()) {
                     logError("데이터 저장 폴더 생성 실패: " + saveDir);
                     return;
                }
                 logDebug("Save directory created: " + saveDir);
            }

            String worldId = client.world.getRegistryKey().getValue().toString().replace(':', '_').replace('/', '_');
            String playerName = client.player.getGameProfile().getName();
            File saveFile = saveDir.resolve(worldId + "_" + playerName + ".dat").toFile();
            logDebug("Preparing to save data to: " + saveFile.getAbsolutePath());

            NbtCompound root = new NbtCompound();
            int stateCount = 0, blockCount = 0, snapshotCount = 0;

            // 청크 상태 저장 (VISIBLE 제외)
            NbtList chunkStatesNbt = new NbtList();
            for (Map.Entry<ChunkPosition, ChunkState> entry : chunkStates.entrySet()) {
                if (entry.getValue() != ChunkState.VISIBLE) {
                    NbtCompound chunkData = new NbtCompound();
                    chunkData.putInt("x", entry.getKey().x);
                    chunkData.putInt("z", entry.getKey().z);
                    chunkData.putString("state", entry.getValue().name());
                    chunkStatesNbt.add(chunkData);
                    stateCount++;
                }
            }
            if (!chunkStatesNbt.isEmpty()) root.put("chunkStates", chunkStatesNbt);

            // 안개 블록 저장 (기본 블록과 다른 경우만)
            NbtList fogBlocksNbt = new NbtList();
            for (Map.Entry<ChunkPosition, BlockState> entry : fogBlocks.entrySet()) {
                if (!entry.getValue().equals(defaultFogBlock)) {
                    NbtCompound blockData = new NbtCompound();
                    blockData.putInt("x", entry.getKey().x);
                    blockData.putInt("z", entry.getKey().z);
                    Identifier blockId = Registries.BLOCK.getId(entry.getValue().getBlock());
                    blockData.putString("block", blockId.toString());
                    fogBlocksNbt.add(blockData);
                    blockCount++;
                }
            }
             if (!fogBlocksNbt.isEmpty()) root.put("fogBlocks", fogBlocksNbt);

            // 스냅샷 데이터 저장 (FOGGED 상태 청크만)
            NbtList snapshotsNbt = new NbtList();
            for (Map.Entry<ChunkPosition, NbtCompound> entry : chunkSnapshots.entrySet()) {
                 if (chunkStates.getOrDefault(entry.getKey(), ChunkState.VISIBLE) == ChunkState.FOGGED) {
                    NbtCompound snapshotData = new NbtCompound();
                    snapshotData.putInt("x", entry.getKey().x);
                    snapshotData.putInt("z", entry.getKey().z);
                    snapshotData.put("data", entry.getValue());
                    snapshotsNbt.add(snapshotData);
                    snapshotCount++;
                 }
            }
             if (!snapshotsNbt.isEmpty()) root.put("snapshots", snapshotsNbt);

            // 파일에 저장
            if (!root.isEmpty()) {
                logDebug("Saving NBT data: " + stateCount + " states, " + blockCount + " blocks, " + snapshotCount + " snapshots.");
                try {
                    NbtIo.writeCompressed(root, saveFile.toPath());
                    logInfo("안개 데이터 저장 완료: " + saveFile.getName());
                } catch (Exception e) {
                    logError("데이터 파일 쓰기 실패: " + e.getMessage(), e);
                }
            } else {
                 logInfo("저장할 안개 데이터가 없습니다. 파일 삭제 시도: " + saveFile.getName());
                 if(saveFile.exists() && saveFile.delete()) {
                      logDebug("  Previous save file deleted.");
                 }
            }

        } catch (Exception e) {
            logError("전장의 안개 데이터 저장 실패: " + e.getMessage(), e);
        }
    }

    /** 게임 시작 시 안개 상태 및 스냅샷 불러오기 */
    public void loadData() {
        logDebug("loadData() called.");
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.world == null || client.player == null) {
                logWarn("데이터 로드 시점 오류: 클라이언트/월드/플레이어 정보 없음.");
                return;
            }

            Path saveDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("fog_data");
            String worldId = client.world.getRegistryKey().getValue().toString().replace(':', '_').replace('/', '_');
            String playerName = client.player.getGameProfile().getName();
            File saveFile = saveDir.resolve(worldId + "_" + playerName + ".dat").toFile();
            logDebug("Attempting to load data from: " + saveFile.getAbsolutePath());

            if (!saveFile.exists()) {
                 logInfo("저장된 안개 데이터 파일 없음: " + saveFile.getName());
                return;
            }

            clearFogData(); // 로드 전 기존 데이터 클리어

            try {
                NbtCompound root = NbtIo.readCompressed(saveFile.toPath(), NbtSizeTracker.ofUnlimitedBytes());
                logInfo("안개 데이터 로드 시작: " + saveFile.getName());
                 int stateCount = 0, blockCount = 0, snapshotCount = 0;

                // 청크 상태 불러오기
                if (root.contains("chunkStates", NbtList.COMPOUND_TYPE)) {
                    NbtList chunkStatesNbt = root.getList("chunkStates", NbtCompound.COMPOUND_TYPE);
                     logDebug("  Loading " + chunkStatesNbt.size() + " chunk states...");
                    for (int i = 0; i < chunkStatesNbt.size(); i++) {
                        NbtCompound chunkData = chunkStatesNbt.getCompound(i);
                        // 필수 키 존재 여부 확인 강화
                        if (chunkData.contains("x") && chunkData.contains("z") && chunkData.contains("state")) {
                            int x = chunkData.getInt("x");
                            int z = chunkData.getInt("z");
                            String stateName = chunkData.getString("state");
                            try {
                                ChunkState state = ChunkState.valueOf(stateName);
                                if (state != ChunkState.VISIBLE) { // VISIBLE은 저장 안했으므로 로드할 필요 없음
                                    chunkStates.put(new ChunkPosition(x, z), state);
                                    stateCount++;
                                }
                            } catch (IllegalArgumentException e) {
                                logWarn("    저장된 데이터에 잘못된 청크 상태 값: " + stateName + " at ("+x+","+z+")");
                            }
                        } else {
                             logWarn("    Invalid chunk state data found in NBT: " + chunkData);
                        }
                    }
                }

                // 안개 블록 불러오기
                 if (root.contains("fogBlocks", NbtList.COMPOUND_TYPE)) {
                    NbtList fogBlocksNbt = root.getList("fogBlocks", NbtCompound.COMPOUND_TYPE);
                     logDebug("  Loading " + fogBlocksNbt.size() + " custom fog blocks...");
                    for (int i = 0; i < fogBlocksNbt.size(); i++) {
                        NbtCompound blockData = fogBlocksNbt.getCompound(i);
                         if (blockData.contains("x") && blockData.contains("z") && blockData.contains("block")) {
                            int x = blockData.getInt("x");
                            int z = blockData.getInt("z");
                            String blockId = blockData.getString("block");
                            Identifier blockIdentifier = safeCreateIdentifier(blockId);
                            if (blockIdentifier != null) {
                                Block block = Registries.BLOCK.get(blockIdentifier);
                                if (block != Blocks.AIR) {
                                    fogBlocks.put(new ChunkPosition(x, z), block.getDefaultState());
                                    blockCount++;
                                } else {
                                     logWarn("    저장된 데이터에 잘못된 블록 ID(AIR): " + blockId + " at ("+x+","+z+")");
                                }
                            } else {
                                 logWarn("    저장된 데이터에 잘못된 블록 ID 형식: " + blockId + " at ("+x+","+z+")");
                            }
                        } else {
                             logWarn("    Invalid fog block data found in NBT: " + blockData);
                        }
                    }
                }

                // 스냅샷 데이터 불러오기
                 if (root.contains("snapshots", NbtList.COMPOUND_TYPE)) {
                    NbtList snapshotsNbt = root.getList("snapshots", NbtCompound.COMPOUND_TYPE);
                     logDebug("  Loading " + snapshotsNbt.size() + " snapshots...");
                    for (int i = 0; i < snapshotsNbt.size(); i++) {
                        NbtCompound snapshotData = snapshotsNbt.getCompound(i);
                         if (snapshotData.contains("x") && snapshotData.contains("z") && snapshotData.contains("data", NbtCompound.COMPOUND_TYPE)) {
                            int x = snapshotData.getInt("x");
                            int z = snapshotData.getInt("z");
                            NbtCompound data = snapshotData.getCompound("data");
                            chunkSnapshots.put(new ChunkPosition(x, z), data);
                            snapshotCount++;
                        } else {
                            logWarn("    Invalid snapshot data found in NBT: " + snapshotData);
                        }
                    }
                }

                logInfo("안개 데이터 로드 완료: " + stateCount + "개 상태, "
                                   + blockCount + "개 커스텀 블록, " + snapshotCount + "개 스냅샷");

            } catch (Exception e) {
                logError("안개 데이터 파일 읽기/파싱 실패: " + e.getMessage(), e);
                clearFogData(); // 로드 실패 시 확실히 초기화
            }

        } catch (Exception e) {
            logError("전장의 안개 데이터 로드 중 오류: " + e.getMessage(), e);
        }
    }

    // --- 상태 조회 메서드 (Mixin 등에서 사용) ---

    /** 주어진 청크의 스냅샷 데이터를 가져옵니다. */
    public static NbtCompound getChunkSnapshot(int x, int z) {
        if (instance == null) {
             // logStaticWarn("getChunkSnapshot called before instance is ready."); // 너무 빈번하게 호출될 수 있음
            return null;
        }
        if (!instance.initialized) {
             // logStaticDebug("getChunkSnapshot called before world initialized."); // 너무 빈번하게 호출될 수 있음
             return null;
        }
        ChunkPosition pos = new ChunkPosition(x, z);
        NbtCompound snapshot = instance.chunkSnapshots.get(pos);
        // logStaticDebug("getChunkSnapshot(" + pos + ") -> " + (snapshot != null ? "Found" : "Not Found")); // 매우 빈번하므로 주석 처리
        return snapshot;
    }

    /** 특정 청크가 숨겨진 상태인지 확인 */
    public static boolean isHiddenChunk(int x, int z) {
        if (instance == null || !instance.initialized) return false;
        ChunkPosition pos = new ChunkPosition(x, z);
        boolean hidden = instance.chunkStates.getOrDefault(pos, ChunkState.VISIBLE) == ChunkState.HIDDEN;
        // logStaticDebug("isHiddenChunk(" + pos + ") -> " + hidden); // 매우 빈번하므로 주석 처리
        return hidden;
    }

    /** 특정 청크가 보이는지 확인 */
    public static boolean isVisibleChunk(int x, int z) {
        if (instance == null) return true; // 안전 기본값
        if (!instance.initialized) return true; // 초기화 안됐으면 기본적으로 보임
        ChunkPosition pos = new ChunkPosition(x, z);
        boolean visible = instance.chunkStates.getOrDefault(pos, ChunkState.VISIBLE) == ChunkState.VISIBLE;
        // logStaticDebug("isVisibleChunk(" + pos + ") -> " + visible); // 매우 빈번하므로 주석 처리
        return visible;
    }

    /** 특정 청크가 안개 상태인지 확인 */
    public static boolean isFoggedChunk(int x, int z) {
        if (instance == null || !instance.initialized) return false;
        ChunkPosition pos = new ChunkPosition(x, z);
        boolean fogged = instance.chunkStates.getOrDefault(pos, ChunkState.VISIBLE) == ChunkState.FOGGED;
        // logStaticDebug("isFoggedChunk(" + pos + ") -> " + fogged); // 매우 빈번하므로 주석 처리
        return fogged;
    }

    /** 특정 청크의 안개 블록 상태 가져오기 */
    public static BlockState getFogBlock(int x, int z) {
        if (instance == null) return Blocks.AIR.getDefaultState();
        if (!instance.initialized) return instance.defaultFogBlock;

        ChunkPosition pos = new ChunkPosition(x, z);
        // VISIBLE 상태일 때는 기본 블록 사용
        if (instance.chunkStates.getOrDefault(pos, ChunkState.VISIBLE) == ChunkState.VISIBLE) {
            // logStaticDebug("getFogBlock(" + pos + ") -> Default (Visible Chunk)"); // 매우 빈번
            return instance.defaultFogBlock;
        }
        BlockState fogBlock = instance.fogBlocks.getOrDefault(pos, instance.defaultFogBlock);
        // logStaticDebug("getFogBlock(" + pos + ") -> " + Registries.BLOCK.getId(fogBlock.getBlock())); // 매우 빈번
        return fogBlock;
    }

    // --- 유틸리티 메서드 ---

    /** 내부 데이터 초기화 */
    private void clearFogData() {
         chunkStates.clear();
         fogBlocks.clear();
         chunkSnapshots.clear();
         logInfo("내부 안개 데이터 초기화 완료.");
    }

    /** 안전하게 월드 렌더러 리로드 */
    private void safeReloadWorldRenderer() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.worldRenderer != null && client.world != null) {
            logInfo("월드 렌더러 리로드 요청.");
            client.execute(client.worldRenderer::reload);
        } else {
            logWarn("월드 렌더러 리로드 불가: 클라이언트/월드/렌더러 준비 안됨.");
        }
    }

    /** 안전하게 Identifier 생성 */
    public Identifier safeCreateIdentifier(String id) {
        if (id == null || id.trim().isEmpty()) return null;
        String processedId = id.trim().toLowerCase(); // 소문자로 처리 권장
        try {
            if (!processedId.contains(":")) {
                processedId = "minecraft:" + processedId;
            }
             Identifier identifier = Identifier.of(processedId);
             // 추가 검증: 실제로 해당 ID의 블록이 존재하는지 확인 (선택적)
             // if (!Registries.BLOCK.containsId(identifier)) {
             //     logWarn("Identifier created, but no block found for: " + identifier);
             //     return null; // 존재하지 않는 블록 ID면 null 반환
             // }
             return identifier;
        } catch (Exception e) { // InvalidIdentifierException 등
            logWarn("유효하지 않은 Identifier 형식: " + id + " (Processed: " + processedId + ")");
            return null;
        }
    }

    /** 청크를 강제로 언로드 (현재 직접 구현 어려움) */
    private void forceUnloadChunk(int x, int z) {
        logWarn("청크 강제 언로드 시도 (현재 미구현): (" + x + ", " + z + ")");
        // MinecraftClient client = MinecraftClient.getInstance();
        // if (client != null && client.world != null && client.world.getChunkManager() instanceof ClientChunkManager) {
        //     ChunkPos pos = new ChunkPos(x, z);
        //     // 직접 unload 호출 어려움
        // }
    }

    // --- 기타 설정 관련 메서드 ---

    /** 기본 안개 블록 설정 */
    public void setDefaultFogBlock(BlockState blockState) {
        if (blockState != null && blockState.getBlock() != Blocks.AIR) {
            this.defaultFogBlock = blockState;
             logInfo("기본 안개 블록 변경됨: " + Registries.BLOCK.getId(blockState.getBlock()));
        } else {
             logWarn("유효하지 않은 기본 안개 블록으로 설정 시도됨.");
        }
    }

    /** 웹소켓 엔드포인트 설정 (API 클라이언트에 위임) */
    public void setWebsocketEndpoint(String endpoint) {
        if (apiClient != null) {
            apiClient.setWebsocketEndpoint(endpoint);
        } else {
             logError("API 클라이언트가 초기화되지 않아 웹소켓 엔드포인트를 설정할 수 없습니다.");
        }
    }

    // --- 로깅 헬퍼 ---
    private static void logInfo(String message) { System.out.println("[FogOfWar] " + message); }
    private static void logWarn(String message) { System.out.println("[FogOfWar WARN] " + message); }
    private static void logError(String message, Throwable throwable) { System.err.println("[FogOfWar ERROR] " + message); if (throwable != null) throwable.printStackTrace(); }
    private static void logError(String message) { logError(message, null); }
    private static void logDebug(String message) { if (DEBUG_MODE) System.out.println("[FogOfWar DEBUG] " + message); }
    // Static 메서드용 로거 (호출 빈도 주의)
    // private static void logStaticDebug(String message) { if (DEBUG_MODE) System.out.println("[FogOfWar Static DEBUG] " + message); }
    // private static void logStaticWarn(String message) { System.out.println("[FogOfWar Static WARN] " + message); }


    // --- 청크 위치 식별 클래스 ---
    public static class ChunkPosition {
        public final int x;
        public final int z;

        public ChunkPosition(int x, int z) { this.x = x; this.z = z; }
        public ChunkPosition(ChunkPos pos) { this.x = pos.x; this.z = pos.z; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ChunkPosition that = (ChunkPosition) o;
            return x == that.x && z == that.z;
        }

        @Override public int hashCode() { return 31 * x + z; }
        @Override public String toString() { return "(" + x + ", " + z + ")"; }
    }
}