package net.civarmymod;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.civarmymod.network.FogAPIClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;

/**
 * 전장의 안개 시스템 클라이언트 구현
 * HTTP/UDP를 통해 버킷 서버의 플러그인과 통신하여 안개 상태를 관리합니다.
 */
@Environment(EnvType.CLIENT)
public class FogOfWarClient implements ClientModInitializer{
    // 싱글톤 인스턴스
    private static FogOfWarClient instance;
    
    /**
     * 싱글톤 인스턴스 가져오기
     */
    public static FogOfWarClient getInstance() {
        return instance;
    }
    
    // 안개 상태 청크 목록
    private Set<ChunkPosition> foggedChunks = new HashSet<>();
    
    // 청크 스냅샷 데이터 (청크 위치 -> NBT 데이터)
    private Map<ChunkPosition, NbtCompound> chunkSnapshots = new HashMap<>();
    
    // 청크 상태 정의
    public enum ChunkState {
        VISIBLE,  // 일반적으로 볼 수 있는 청크
        FOGGED,   // 안개 처리된 청크
        HIDDEN    // 숨겨진 청크 (렌더링 안됨)
    }
    
    // 청크 상태 맵
    private final Map<ChunkPosition, ChunkState> chunkStates = new ConcurrentHashMap<>();
    
    // 안개 블록 맵 (청크별 다른 블록 적용 가능)
    private final Map<ChunkPosition, BlockState> fogBlocks = new ConcurrentHashMap<>();
    
    // 기본 안개 블록
    private BlockState defaultFogBlock = Blocks.GRAY_CONCRETE.getDefaultState();
    
    // API 클라이언트
    private FogAPIClient apiClient;
    
    @Override
    public void onInitializeClient() {
        // 싱글톤 인스턴스 설정 (가장 먼저 설정)
        instance = this;
        
        try {
            // 설정 불러오기
            loadConfig();
            
            // 데이터 불러오기
            loadData();

            // API 클라이언트 초기화
            apiClient = new FogAPIClient();
            apiClient.setDataConsumer(this::updateFromApiResponse);
            
            // 웹소켓 연결 시도 (서버가 없는 경우 예외 처리)
            try {
                apiClient.connectWebSocket();
                System.out.println("웹소켓 연결 시도 중...");
            } catch (Exception e) {
                System.out.println("웹소켓 연결 실패: " + e.getMessage());
                System.out.println("서버가 열려있지 않습니다. 오프라인 모드로 실행합니다.");
            }
            
            // 모드 초기화 완료 메시지
            System.out.println("전장의 안개 모드가 초기화되었습니다.");
        } catch (Exception e) {
            System.err.println("전장의 안개 모드 초기화 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 주어진 청크가 안개 상태인지 확인합니다.
     */
    public static boolean isFoggedChunk(int x, int z) {
        return getInstance().foggedChunks.contains(new ChunkPosition(x, z));
    }
    
    /**
     * 주어진 청크의 스냅샷 데이터를 가져옵니다.
     */
    public static NbtCompound getChunkSnapshot(int x, int z) {
        return getInstance().chunkSnapshots.get(new ChunkPosition(x, z));
    }
    
    /**
     * 특정 청크가 숨겨져 있는지 확인
     */
    public static boolean isHiddenChunk(int x, int z) {
        ChunkPosition pos = new ChunkPosition(x, z);
        ChunkState state = getInstance().chunkStates.getOrDefault(pos, ChunkState.HIDDEN);
        return state == ChunkState.HIDDEN;
    }
    
    /**
     * 특정 청크의 안개 블록 상태 가져오기
     */
    public static BlockState getFogBlock(int x, int z) {
        ChunkPosition pos = new ChunkPosition(x, z);
        return getInstance().fogBlocks.getOrDefault(pos, getInstance().defaultFogBlock);
    }
    
    /**
     * 안개 청크 추가
     */
    public void addFoggedChunk(int x, int z) {
        foggedChunks.add(new ChunkPosition(x, z));
    }
    
    /**
     * 안개 청크 제거
     */
    public void removeFoggedChunk(int x, int z) {
        foggedChunks.remove(new ChunkPosition(x, z));
        chunkSnapshots.remove(new ChunkPosition(x, z));
    }
    
    /**
     * 모든 안개 청크 초기화
     */
    public void clearFoggedChunks() {
        foggedChunks.clear();
        chunkSnapshots.clear();
    }
    
    /**
     * 청크 스냅샷 설정
     */
    public void setChunkSnapshot(int x, int z, NbtCompound snapshot) {
        chunkSnapshots.put(new ChunkPosition(x, z), snapshot);
    }
    
    /**
     * 기본 안개 블록 설정
     */
    public void setDefaultFogBlock(BlockState blockState) {
        this.defaultFogBlock = blockState;
    }
    
    /**
     * API 엔드포인트 설정
     */
    public void setApiEndpoint(String endpoint) {
        apiClient.setApiEndpoint(endpoint);
    }
    
    /**
     * 웹소켓 엔드포인트 설정
     */
    public void setWebsocketEndpoint(String endpoint) {
        if (apiClient != null) {
            apiClient.setWebsocketEndpoint(endpoint);
        }
    }
    
    /**
     * API 응답 처리 업데이트
     */
    public void updateFromApiResponse(JsonObject jsonResponse) {
        try {
            if (jsonResponse.has("foggedChunks")) {
                JsonArray foggedChunks = jsonResponse.getAsJsonArray("foggedChunks");
                
                // 안개가 적용된 청크 세트 (처리 후 지워질 청크 확인용)
                Set<ChunkPosition> updatedChunks = new HashSet<>();
                
                // 응답에서 각 청크 처리
                for (JsonElement element : foggedChunks) {
                    JsonObject chunkData = element.getAsJsonObject();
                    
                    int x = chunkData.get("x").getAsInt();
                    int z = chunkData.get("z").getAsInt();
                    ChunkPosition chunkPos = new ChunkPosition(x, z);
                    updatedChunks.add(chunkPos);
                    
                    // 청크 상태 설정
                    if (chunkData.has("state")) {
                        String stateStr = chunkData.get("state").getAsString().toUpperCase();
                        try {
                            ChunkState state = ChunkState.valueOf(stateStr);
                            chunkStates.put(chunkPos, state);
                        } catch (IllegalArgumentException e) {
                            System.out.println("잘못된 청크 상태: " + stateStr);
                            // 기본값으로 FOGGED 설정
                            chunkStates.put(chunkPos, ChunkState.FOGGED);
                        }
                    } else {
                        // 기본값은 FOGGED
                        chunkStates.put(chunkPos, ChunkState.FOGGED);
                    }
                    
                    // 안개 블록 설정
                    if (chunkData.has("fogBlock")) {
                        String blockId = chunkData.get("fogBlock").getAsString();
                        try {
                            // Identifier.of() 메서드 사용
                            BlockState blockState = Registries.BLOCK.get(Identifier.of(blockId)).getDefaultState();
                            fogBlocks.put(chunkPos, blockState);
                        } catch (Exception e) {
                            System.out.println("잘못된 블록 ID: " + blockId);
                            fogBlocks.put(chunkPos, defaultFogBlock);
                        }
                    } else {
                        fogBlocks.put(chunkPos, defaultFogBlock);
                    }
                    
                    // 스냅샷 데이터가 있으면 저장 (기존 로직 유지)
                    if (chunkData.has("snapshot")) {
                        String snapshotBase64 = chunkData.get("snapshot").getAsString();
                        NbtCompound snapshot = FogAPIClient.decodeSnapshot(snapshotBase64);
                        
                        if (snapshot != null) {
                            // 스냅샷 저장 처리
                            chunkSnapshots.put(new ChunkPosition(x, z), snapshot);
                        }
                    }
                }
                
                // 현재 가시 상태에 없는 청크는 HIDDEN으로 설정
                if (jsonResponse.has("resetState") && jsonResponse.get("resetState").getAsBoolean()) {
                    Set<ChunkPosition> toRemove = new HashSet<>();
                    for (ChunkPosition pos : chunkStates.keySet()) {
                        if (!updatedChunks.contains(pos)) {
                            toRemove.add(pos);
                        }
                    }
                    
                    // 오래된 청크 상태 제거
                    for (ChunkPosition pos : toRemove) {
                        chunkStates.remove(pos);
                        fogBlocks.remove(pos);
                        chunkSnapshots.remove(pos);
                    }
                }
                
                // 게임 세계 리로드 요청
                MinecraftClient.getInstance().worldRenderer.reload();
            }
        } catch (Exception e) {
            System.out.println("API 응답 처리 오류: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 서버로부터 안개 데이터를 수동으로 요청합니다.
     * 서버 측 변경사항이 있을 때만 호출하세요.
     */
    private void updateFogData() {
        // 웹소켓 연결이 설정되어 있으면 새로운 요청을 보내지 않음
        if (apiClient != null) {
            // 웹소켓 연결 상태 확인
            if (!apiClient.isConnected()) {
                apiClient.connectWebSocket();
            }
        }
    }
    
    /**
     * 게임 종료 시 안개 상태 및 스냅샷 저장
     */
    public void saveData() {
        try {
            // 저장 디렉토리 확인
            Path saveDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("fog_data");
            if (!saveDir.toFile().exists()) saveDir.toFile().mkdirs();
            
            // 월드 ID와 플레이어를 기반으로 파일명 생성
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null || client.player == null) return;
            
            String worldId = client.world.getRegistryKey().getValue().toString().replace(':', '_');
            String playerName = client.player.getGameProfile().getName();
            File saveFile = saveDir.resolve(worldId + "_" + playerName + ".dat").toFile();
            
            // 데이터 준비
            NbtCompound root = new NbtCompound();
            
            // 청크 상태 저장
            NbtList chunkStatesNbt = new NbtList();
            for (Map.Entry<ChunkPosition, ChunkState> entry : chunkStates.entrySet()) {
                NbtCompound chunkData = new NbtCompound();
                chunkData.putInt("x", entry.getKey().x);
                chunkData.putInt("z", entry.getKey().z);
                chunkData.putString("state", entry.getValue().name());
                chunkStatesNbt.add(chunkData);
            }
            root.put("chunkStates", chunkStatesNbt);
            
            // 안개 블록 저장
            NbtList fogBlocksNbt = new NbtList();
            for (Map.Entry<ChunkPosition, BlockState> entry : fogBlocks.entrySet()) {
                NbtCompound blockData = new NbtCompound();
                blockData.putInt("x", entry.getKey().x);
                blockData.putInt("z", entry.getKey().z);
                blockData.putString("block", Registries.BLOCK.getId(entry.getValue().getBlock()).toString());
                fogBlocksNbt.add(blockData);
            }
            root.put("fogBlocks", fogBlocksNbt);
            
            // 스냅샷 데이터 저장
            NbtList snapshotsNbt = new NbtList();
            for (Map.Entry<ChunkPosition, NbtCompound> entry : chunkSnapshots.entrySet()) {
                NbtCompound snapshotData = new NbtCompound();
                snapshotData.putInt("x", entry.getKey().x);
                snapshotData.putInt("z", entry.getKey().z);
                snapshotData.put("data", entry.getValue());
                snapshotsNbt.add(snapshotData);
            }
            root.put("snapshots", snapshotsNbt);
            
            // 파일에 저장
            try {
                NbtIo.writeCompressed(root, saveFile.toPath());
                System.out.println("안개 데이터 저장 완료: " + saveFile.getPath());
            } catch (Exception e) {
                System.out.println("데이터 저장 실패: " + e.getMessage());
                e.printStackTrace();
            }
        } catch (Exception e) {
            System.out.println("전장의 안개 데이터 저장 실패: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 게임 시작 시 안개 상태 및 스냅샷 불러오기
     */
    public void loadData() {
        try {
            // 저장 파일 확인
            Path saveDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("fog_data");
            if (!saveDir.toFile().exists()) return;
            
            // 월드 ID와 플레이어를 기반으로 파일명 생성
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null || client.player == null) return;
            
            String worldId = client.world.getRegistryKey().getValue().toString().replace(':', '_');
            String playerName = client.player.getGameProfile().getName();
            File saveFile = saveDir.resolve(worldId + "_" + playerName + ".dat").toFile();
            
            if (!saveFile.exists()) return;
            
            // 파일에서 데이터 로드
            try {
                NbtCompound root = NbtIo.readCompressed(saveFile.toPath(), NbtSizeTracker.ofUnlimitedBytes());
                System.out.println("안개 데이터 로드 시작");
                
                // 청크 상태 불러오기
                if (root.contains("chunkStates")) {
                    NbtList chunkStatesNbt = root.getList("chunkStates", 10);
                    for (int i = 0; i < chunkStatesNbt.size(); i++) {
                        NbtCompound chunkData = chunkStatesNbt.getCompound(i);
                        int x = chunkData.getInt("x");
                        int z = chunkData.getInt("z");
                        String stateName = chunkData.getString("state");
                        
                        try {
                            ChunkState state = ChunkState.valueOf(stateName);
                            chunkStates.put(new ChunkPosition(x, z), state);
                        } catch (IllegalArgumentException e) {
                            System.out.println("잘못된 청크 상태: " + stateName);
                        }
                    }
                }
                
                // 안개 블록 불러오기
                if (root.contains("fogBlocks")) {
                    NbtList fogBlocksNbt = root.getList("fogBlocks", 10);
                    for (int i = 0; i < fogBlocksNbt.size(); i++) {
                        NbtCompound blockData = fogBlocksNbt.getCompound(i);
                        int x = blockData.getInt("x");
                        int z = blockData.getInt("z");
                        String blockId = blockData.getString("block");
                        
                        try {
                            // Identifier.of() 메서드 사용
                            Block block = Registries.BLOCK.get(Identifier.of(blockId));
                            fogBlocks.put(new ChunkPosition(x, z), block.getDefaultState());
                        } catch (Exception e) {
                            System.out.println("잘못된 블록 ID: " + blockId);
                        }
                    }
                }
                
                // 스냅샷 데이터 불러오기
                if (root.contains("snapshots")) {
                    NbtList snapshotsNbt = root.getList("snapshots", 10);
                    for (int i = 0; i < snapshotsNbt.size(); i++) {
                        NbtCompound snapshotData = snapshotsNbt.getCompound(i);
                        int x = snapshotData.getInt("x");
                        int z = snapshotData.getInt("z");
                        NbtCompound data = snapshotData.getCompound("data");
                        
                        chunkSnapshots.put(new ChunkPosition(x, z), data);
                    }
                }
                
                System.out.println("안개 데이터 로드 완료: " + saveFile.getPath());
                
                // 월드 리렌더링
                if (client.worldRenderer != null) {
                    client.worldRenderer.reload();
                }
            } catch (Exception e) {
                System.out.println("안개 데이터 로드 실패: " + e.getMessage());
                e.printStackTrace();
            }
        } catch (Exception e) {
            System.out.println("전장의 안개 데이터 로드 실패: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 설정 불러오기
     */
    private void loadConfig() {
        // TODO: 설정 파일에서 설정 불러오기
        System.out.println("설정을 불러오는 중...");
    }
    
    /**
     * 청크 위치를 식별하는 클래스
     */
    public static class ChunkPosition {
        private final int x;
        private final int z;
        
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
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ChunkPosition that = (ChunkPosition) o;
            return x == that.x && z == that.z;
        }
        
        @Override
        public int hashCode() {
            return 31 * x + z;
        }
    }
}
