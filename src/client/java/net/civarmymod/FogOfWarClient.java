package net.civarmymod;

import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement; // <--- 이 줄 추가
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

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
    private Map<ChunkPosition, byte[]> chunkSnapshots = new ConcurrentHashMap<>();
    public enum ChunkState { VISIBLE, FOGGED, HIDDEN }
    private final Map<ChunkPosition, ChunkState> chunkStates = new ConcurrentHashMap<>();
    private final Map<ChunkPosition, BlockState> fogBlocks = new ConcurrentHashMap<>();
    private BlockState defaultFogBlock = Blocks.GRAY_CONCRETE.getDefaultState();

    // --- 월드 차원 정보 ---
    private int worldBottomY = 0; // 월드 최저 Y 좌표, 스냅샷 생성 시 업데이트
    private int worldTotalHeight = 256; // 월드 전체 높이, 스냅샷 생성 시 업데이트

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
                    ChunkState state = ChunkState.VISIBLE; // 기본값을 VISIBLE로 변경 (안전한 기본값)
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

                    // 상태가 HIDDEN으로 설정되었다면 해당 청크를 강제로 언로드 시도
                    if (state == ChunkState.HIDDEN) {
                         // Mixin 클래스의 인스턴스에 접근하여 forceUnloadChunk 호출 필요
                         // ClientChunkManager 인스턴스를 가져와서 캐스팅해야 함
                         MinecraftClient client = MinecraftClient.getInstance();
                         if (client != null && client.world != null && client.world.getChunkManager() instanceof ClientChunkManager) {
                             try {
                                 // Use the accessor to call the original unload method
                                //  ((ClientChunkManagerAccessor)client.world.getChunkManager()).invokeUnload(new ChunkPos(x, z));
                                //  ((ClientChunkManagerAccessor)client.world.getChunkManager()).invokeUnload(new ChunkPos(x, z+4));
                                 logDebug("청크 언로드 요청 완료: (" + x + ", " + z + ")"); // 성공 로그
                             } catch (Exception e) {
                                 logError("청크 언로드 중 오류 발생: (" + x + ", " + z + ") - " + e.getMessage(), e); // 오류 로그
                             }
                         }
                    }

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


                    // // 3. 스냅샷 데이터 저장/제거
                    // if (state == ChunkState.FOGGED) {
                    //     if (chunkData.has("snapshot") && chunkData.get("snapshot").isJsonPrimitive()) {
                    //         // 서버에서 스냅샷 데이터를 제공한 경우
                    //         String snapshotBase64 = chunkData.get("snapshot").getAsString();
                    //         logDebug("  Attempting to decode snapshot for FOGGED chunk...");
                    //         NbtCompound snapshot = FogAPIClient.decodeSnapshot(snapshotBase64);
                    //         if (snapshot != null) {
                    //             chunkSnapshots.put(chunkPos, snapshot);
                    //             logDebug("  Snapshot from server decoded and stored.");
                    //         } else {
                    //             logError("  청크 (" + x + ", " + z + ") 스냅샷 디코딩 실패!");
                               
                                
                    //         }
                    //     } else {
                           
                    //         logDebug("  No snapshot data from server, creating local snapshot for chunk (" + x + ", " + z + ")");
                        
                    //     }
                    // } else {
                    //     // FOGGED 상태가 아니면 스냅샷 데이터 제거
                    //     if(chunkSnapshots.remove(chunkPos) != null) {
                    //          logDebug("  Removed snapshot data for non-FOGGED chunk.");
                    //     }
                    // }
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

            // 스냅샷 데이터 저장 (FOGGED 상태 청크만, byte[] 사용)
            NbtList snapshotsNbt = new NbtList();
            for (Map.Entry<ChunkPosition, byte[]> entry : chunkSnapshots.entrySet()) {
                 if (chunkStates.getOrDefault(entry.getKey(), ChunkState.VISIBLE) == ChunkState.FOGGED) {
                    NbtCompound snapshotData = new NbtCompound();
                    snapshotData.putInt("x", entry.getKey().x);
                    snapshotData.putInt("z", entry.getKey().z);
                    // byte[] 데이터를 NBT에 저장
                    snapshotData.putByteArray("data", entry.getValue());
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

                // 스냅샷 데이터 불러오기 (byte[] 사용)
                if (root.contains("snapshots", NbtList.COMPOUND_TYPE)) {
                    NbtList snapshotsNbt = root.getList("snapshots", NbtCompound.COMPOUND_TYPE);
                     logDebug("  Loading " + snapshotsNbt.size() + " snapshots...");
                    for (int i = 0; i < snapshotsNbt.size(); i++) {
                        NbtCompound snapshotData = snapshotsNbt.getCompound(i);
                         if (snapshotData.contains("x") && snapshotData.contains("z") && snapshotData.contains("data", NbtElement.BYTE_ARRAY_TYPE)) {
                            int x = snapshotData.getInt("x");
                            int z = snapshotData.getInt("z");
                            // NBT에서 byte[] 데이터를 로드
                            byte[] data = snapshotData.getByteArray("data");
                            if (data != null && data.length > 0) { // 데이터 유효성 검사 (선택적)
                                chunkSnapshots.put(new ChunkPosition(x, z), data);
                                snapshotCount++;
                            } else {
                                logWarn("    청크 (" + x + ", " + z + ")의 스냅샷 데이터가 비어 있거나 유효하지 않습니다.");
                            }
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
    public static byte[] getChunkSnapshot(int x, int y, int z) {
        if (instance == null) {
             // logStaticWarn("getChunkSnapshot called before instance is ready."); // 너무 빈번하게 호출될 수 있음
            return null;
        }
        if (!instance.initialized) {
             // logStaticDebug("getChunkSnapshot called before world initialized."); // 너무 빈번하게 호출될 수 있음
             return null;
        }
        ChunkPosition pos = new ChunkPosition(x >> 4, z >> 4);
        byte[] snapshot = instance.chunkSnapshots.get(pos);
        // logStaticDebug("getChunkSnapshot(" + pos + ") -> " + (snapshot != null ? "Found" : "Not Found")); // 매우 빈번하므로 주석 처리
        return snapshot;
    }

    /** 특정 청크가 숨겨진 상태인지 확인 */
    public static boolean isHiddenChunk(int x, int z) {
        if (instance == null || !instance.initialized) return true; // 기본값을 HIDDEN으로 변경
        ChunkPosition pos = new ChunkPosition(x, z);
        boolean hidden = instance.chunkStates.getOrDefault(pos, ChunkState.HIDDEN) == ChunkState.HIDDEN; // 기본값을 HIDDEN으로 변경
        // logStaticDebug("isHiddenChunk(" + pos + ") -> " + hidden); // 매우 빈번하므로 주석 처리
        return hidden;
    }

    /** 특정 청크가 보이는지 확인 */
    public static boolean isVisibleChunk(int x, int z) {
        if (instance == null) return false;
        if (!instance.initialized) return false; // 초기화 안됐으면 기본적으로 숨김
        ChunkPosition pos = new ChunkPosition(x, z);
        // 맵에 없으면 HIDDEN으로 간주. VISIBLE 상태는 명시적으로 맵에 있어야 함.
        boolean visible = instance.chunkStates.getOrDefault(pos, ChunkState.HIDDEN) == ChunkState.VISIBLE;
        // logStaticDebug("isVisibleChunk(" + pos + ") -> " + visible); // 매우 빈번하므로 주석 처리
        return visible;
    }

    /** 특정 청크가 안개 상태인지 확인 */
    public static boolean isFoggedChunk(int x, int z) {
        if (instance == null || !instance.initialized) return false; // 안개는 기본적으로 false로 유지
        ChunkPosition pos = new ChunkPosition(x, z);
        boolean fogged = instance.chunkStates.getOrDefault(pos, ChunkState.HIDDEN) == ChunkState.FOGGED; // 기본값을 HIDDEN으로 변경
        // logStaticDebug("isFoggedChunk(" + pos + ") -> " + fogged); // 매우 빈번하므로 주석 처리
        return fogged;
    }
    
    /**
     * 특정 청크의 원래 블록 상태를 가져옵니다.
     * 이 메서드는 fogged 상태인 청크의 원래 블록 상태를 반환합니다.
     * 
     * @param x 청크 X 좌표
     * @param y 블록 Y 좌표
     * @param z 청크 Z 좌표
     * @return 원래 블록 상태 (스냅샷이 없거나 공기/기타는 null 또는 Blocks.AIR, 고체는 Blocks.STONE, 액체는 Blocks.WATER)
     */
    public static BlockState getOriginalBlockState(int x, int y, int z) {
        if (instance == null || !instance.initialized) return null;
        
        // 청크 좌표 계산
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        ChunkPosition chunkPos = new ChunkPosition(chunkX, chunkZ);
        
        // 해당 청크의 스냅샷 가져오기
        byte[] snapshot = instance.chunkSnapshots.get(chunkPos);
        if (snapshot == null) return null; // 스냅샷 자체가 없음
        
        try {
            // 청크 내 상대 좌표 계산
            int relX = x & 15;
            int relZ = z & 15;
            int relY = y - instance.worldBottomY;

            // Y 좌표가 스냅샷 범위 내에 있는지 확인
            if (relY < 0 || relY >= instance.worldTotalHeight) {
                // logStaticDebug("getOriginalBlockState: Y out of bounds. y:" + y + " relY:" + relY); // 너무 빈번할 수 있음
                return null; // Y 좌표가 스냅샷 범위를 벗어남
            }
            
            int index = (relY * 16 * 16) + (relX * 16) + relZ;

            if (index >= 0 && index < snapshot.length) {
                byte blockType = snapshot[index];
                if (blockType == 1) { // Solid
                    return Blocks.STONE.getDefaultState(); // 고체 마커
                } else if (blockType == 2) { // Liquid
                    return Blocks.WATER.getDefaultState(); // 액체 마커
                } else { // Air or other (0)
                    return Blocks.AIR.getDefaultState(); // 공기 또는 기타
                }
            } 
        } catch (Exception e) {
            if (DEBUG_MODE) {
                logError("블록 상태 복원 중 오류: " + e.getMessage(), e);
            }
        }
        return null; // 예외 발생 시 또는 기타 경우
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
    public static Identifier safeCreateIdentifier(String id) {
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
            if (instance != null) {
                instance.logWarn("유효하지 않은 Identifier 형식: " + id + " (Processed: " + processedId + ")");
            }
            return null;
        }
    }

    /**
     * 특정 청크의 현재 상태를 가져옵니다.
     * @param x 청크 X 좌표
     * @param z 청크 Z 좌표
     * @return 해당 청크의 상태. 맵에 없으면 HIDDEN을 반환합니다.
     */
    public ChunkState getChunkState(int x, int z) {
        return chunkStates.getOrDefault(new ChunkPosition(x, z), ChunkState.HIDDEN);
    }

    /**
     * 특정 청크의 상태를 설정합니다.
     * @param x 청크 X 좌표
     * @param z 청크 Z 좌표
     * @param state 설정할 청크 상태
     */
    public void setChunkState(int x, int z, ChunkState state) {
        logDebug("setChunkState(" + x + ", " + z + ", " + state + ") 호출됨. Thread: " + Thread.currentThread().getName());
        ChunkPosition pos = new ChunkPosition(x, z);
        ChunkState previousState = chunkStates.get(pos); // 이전 상태 확인 (로깅용)

        switch (state) {
            case VISIBLE:
                chunkStates.put(pos, ChunkState.VISIBLE);
                fogBlocks.remove(pos);   // Visible 청크는 커스텀 안개 블록이 필요 없음
                chunkSnapshots.remove(pos); // Visible 청크는 스냅샷이 필요 없음
                break;
            case FOGGED:
                chunkStates.put(pos, ChunkState.FOGGED);
                fogBlocks.put(pos, defaultFogBlock);
                // 스냅샷 생성 및 저장
                generateAndStoreChunkSnapshot(pos);
                break;
            case HIDDEN:
                if (previousState != null && previousState != ChunkState.HIDDEN) { // 맵에 있었고 HIDDEN이 아니었던 경우에만 로그
                    logDebug("청크 (" + x + ", " + z + ") 상태를 HIDDEN으로 변경 (맵에서 제거). 이전 상태: " + previousState);
                } else if (previousState == null) {
                    // 맵에 없었으므로 (기본 HIDDEN), 별도 로그는 불필요하거나, 원한다면 추가
                }
                chunkStates.remove(pos); // HIDDEN은 기본 상태이므로 맵에서 제거하여 메모리 절약
                fogBlocks.remove(pos);   // 관련 안개 블록 정보도 제거
                chunkSnapshots.remove(pos); // 관련 스냅샷 정보도 제거
                break;
        }
        // 상태 변경이 실제로 일어났거나, VISIBLE/FOGGED로 설정된 경우 로그 (HIDDEN으로의 변경은 위에서 상세 로깅)
        if ((previousState != state) || (state == ChunkState.VISIBLE || state == ChunkState.FOGGED)) {
             logDebug("청크 (" + x + ", " + z + ") 상태 최종 설정: " + state + (previousState == state ? " (변경 없음, 상태 유지)" : ""));
        }
    }

    /**
     * 지정된 청크의 스냅샷을 생성하고 chunkSnapshots 맵에 저장합니다.
     * @param chunkPosition 스냅샷을 생성할 청크의 위치
     * @return 생성된 스냅샷 데이터, 실패 시 null
     */
    private byte[] generateAndStoreChunkSnapshot(ChunkPosition chunkPosition) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) {
            logWarn("월드 또는 클라이언트가 null이므로 청크 (" + chunkPosition.x + ", " + chunkPosition.z + ") 스냅샷을 생성/저장할 수 없습니다.");
            return null;
        }
        ClientWorld world = client.world;
        ChunkPos mcChunkPos = new ChunkPos(chunkPosition.x, chunkPosition.z);

        // 월드 차원 정보 업데이트 (스냅샷 생성/해석에 사용)
        this.worldBottomY = world.getBottomY();
        this.worldTotalHeight = world.getHeight();

        // 고체/액체 블록 위치만 저장하는 스냅샷 생성
        byte[] snapshotData = internalCreateChunkSnapshotData(world, mcChunkPos);

        if (snapshotData != null) {
            chunkSnapshots.put(chunkPosition, snapshotData);
            logDebug("청크 (" + chunkPosition.x + ", " + chunkPosition.z + ")의 스냅샷 저장됨. 배열 크기: " + snapshotData.length + " bytes");
            return snapshotData;
        } else {
            logWarn("청크 (" + chunkPosition.x + ", " + chunkPosition.z + ") 스냅샷 생성 실패.");
            return null;
        }
    }

    /**
     * 청크 내 고체 및 액체 블록의 위치 정보를 담은 스냅샷 데이터를 생성합니다.
     * @param world 클라이언트 월드
     * @param chunkPos 청크 위치
     * @return 스냅샷 데이터 (byte 배열, 0: 공기/기타, 1: 고체 블록, 2: 액체 블록), 실패 시 null
     */
    private byte[] internalCreateChunkSnapshotData(ClientWorld world, ChunkPos chunkPos) {
        int minX = chunkPos.getStartX();
        int minZ = chunkPos.getStartZ();
        int minY = world.getBottomY();
        int worldHeight = world.getHeight(); // Y 범위 크기 (예: 384)
        int arraySize = 16 * worldHeight * 16;
        byte[] snapshot = new byte[arraySize];

        BlockPos.Mutable mutablePos = new BlockPos.Mutable();
        long startTime = System.nanoTime(); // 성능 측정 시작

        try {
            for (int relY = 0; relY < worldHeight; relY++) {
                int worldY = minY + relY;
                for (int relX = 0; relX < 16; relX++) {
                    for (int relZ = 0; relZ < 16; relZ++) {
                        mutablePos.set(minX + relX, worldY, minZ + relZ);
                        BlockState blockState = world.getBlockState(mutablePos);

                        int valueToStore = 0; // 기본값: 공기 또는 기타
                        if (!blockState.getFluidState().isEmpty()) {
                            valueToStore = 2; // 액체 블록
                        } else if (blockState.isSolidBlock(world, mutablePos)) {
                            valueToStore = 1; // 고체 블록
                        }

                        if (valueToStore != 0) {
                            // 인덱스 계산: Y가 가장 바깥쪽 루프이므로 Y축 우선 (Y * width * depth + X * depth + Z)
                            int index = (relY * 16 * 16) + (relX * 16) + relZ;
                            if (index >= 0 && index < arraySize) { // 배열 범위 확인 (필수)
                                snapshot[index] = (byte) valueToStore;
                            }
                        }
                        // 0은 기본값이므로 else 처리는 불필요
                    }
                }
            }
            long endTime = System.nanoTime();
            logDebug("청크 (" + chunkPos.x + ", " + chunkPos.z + ") 스냅샷 생성 완료. 소요 시간: " + (endTime - startTime) / 1_000_000 + " ms");
            return snapshot;
        } catch (Exception e) {
            logError("청크 (" + chunkPos.x + ", " + chunkPos.z + ") 스냅샷 생성 중 오류 발생", e);
            return null;
        }
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
