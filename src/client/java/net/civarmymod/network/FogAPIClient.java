package net.civarmymod.network;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.enums.ReadyState; // ReadyState enum import
import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.civarmymod.NPCManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

/**
 * 서버 API와 통신하는 클라이언트
 * 웹소켓을 통해 안개 데이터를 받아옵니다.
 * 디버그 로그 추가됨. isConnecting() 오류 수정됨.
 */
@Environment(EnvType.CLIENT)
public class FogAPIClient {
    private static final Gson GSON = new GsonBuilder().create();
    // 디버그 플래그 추가 (FogConfig 등에서 제어 가능하도록 확장 가능)
    private static final boolean DEBUG_MODE = true; // true로 설정하면 상세 로그 출력
    
    // 싱글톤 인스턴스
    private static FogAPIClient instance;
    
    /**
     * 싱글톤 인스턴스 가져오기
     */
    public static FogAPIClient getInstance() {
        return instance;
    }

    private String websocketEndpoint = "ws://localhost:8080/api/fog/ws";
    private UUID playerUuid;
    private FogWebSocketClient webSocketClient;
    private Consumer<JsonObject> dataConsumer;

    public FogAPIClient() {
        // 싱글톤 인스턴스 설정
        instance = this;
        
        // MinecraftClient 인스턴스가 아직 준비되지 않았을 수 있으므로 주의
        // 생성자보다는 실제 사용 시점에 UUID를 가져오는 것이 더 안전할 수 있음
        // 예: connectWebSocket 내부에서 가져오기
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.getSession() != null) {
                this.playerUuid = client.getSession().getUuidOrNull();
            }
        } catch (Exception e) {
             System.err.println("[FogAPIClient WARN] Could not get player UUID during construction: " + e.getMessage());
             this.playerUuid = null; // 초기값 null 설정
        }

        if (DEBUG_MODE) {
            System.out.println("[FogAPIClient DEBUG] Initialized. Player UUID will be checked on connect.");
        }
    }

    /**
     * API 엔드포인트 설정
     */
    public void setApiEndpoint(String endpoint) {
        if (DEBUG_MODE) {
            System.out.println("[FogAPIClient DEBUG] Setting API Endpoint (raw): " + endpoint);
        }
        String oldEndpoint = this.websocketEndpoint;
        if (endpoint == null || endpoint.trim().isEmpty()) {
             System.err.println("[FogAPIClient WARN] Attempted to set an empty or null API endpoint. Keeping old: " + oldEndpoint);
             return;
        }

        // HTTP/WS 스키마 및 경로 처리 강화
        String processedEndpoint = endpoint.trim();
        if (processedEndpoint.startsWith("http://")) {
            processedEndpoint = "ws://" + processedEndpoint.substring(7);
        } else if (processedEndpoint.startsWith("https://")) {
            processedEndpoint = "wss://" + processedEndpoint.substring(8);
        } else if (!processedEndpoint.startsWith("ws://") && !processedEndpoint.startsWith("wss://")) {
            processedEndpoint = "ws://" + processedEndpoint; // 기본 ws 스키마 추가
        }

        // 기본 경로 추가 (이미 경로가 있는 경우 제외) - 서버 구현에 따라 조정 필요
        if (!processedEndpoint.matches(".*/api/fog/ws/?$")) { // 정규식으로 경로 확인 (간단 예시)
             if (processedEndpoint.endsWith("/")) {
                 processedEndpoint += "api/fog/ws";
             } else {
                 processedEndpoint += "/api/fog/ws";
             }
        }

        this.websocketEndpoint = processedEndpoint;

        if (DEBUG_MODE) {
            System.out.println("[FogAPIClient DEBUG] Calculated WebSocket Endpoint: " + this.websocketEndpoint);
        }

        // 엔드포인트 변경 시 재연결 로직
        if (!this.websocketEndpoint.equals(oldEndpoint) && webSocketClient != null && webSocketClient.isOpen()) {
            if (DEBUG_MODE) {
                System.out.println("[FogAPIClient DEBUG] Endpoint changed, attempting to reconnect...");
            }
            // 연결 종료 요청 (비동기)
            disconnectWebSocket();
            // 연결 시도는 외부 로직(예: onWorldJoin)에서 다시 하도록 유도하거나,
            // 여기에 짧은 지연 후 connectWebSocket() 호출 추가 가능
            // new Thread(() -> { try { Thread.sleep(100); connectWebSocket(); } catch (InterruptedException ignored) {} }).start();
        } else if (!this.websocketEndpoint.equals(oldEndpoint)) {
             if (DEBUG_MODE) {
                 System.out.println("[FogAPIClient DEBUG] Endpoint changed, will connect on next attempt.");
             }
        }
    }

    /**
     * 웹소켓 엔드포인트 설정 (setApiEndpoint과 로직 통합 가능성 검토)
     */
    public void setWebsocketEndpoint(String endpoint) {
        setApiEndpoint(endpoint); // 로직 통합
    }

    /**
     * 데이터 수신 핸들러 설정
     */
    public void setDataConsumer(Consumer<JsonObject> consumer) {
        this.dataConsumer = consumer;
        if (DEBUG_MODE) {
            System.out.println("[FogAPIClient DEBUG] Data consumer set: " + (consumer != null));
        }
    }

    /**
     * 웹소켓 연결 상태 확인
     */
    public boolean isConnected() {
        // ReadyState.OPEN 상태인지 확인
        return webSocketClient != null && webSocketClient.getReadyState() == ReadyState.OPEN;
    }

    /**
     * 웹소켓 연결 시작
     */
    public void connectWebSocket() {
        // 플레이어 UUID 다시 확인 (생성자에서 실패했을 수 있음)
        if (this.playerUuid == null) {
             try {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && client.getSession() != null) {
                    this.playerUuid = client.getSession().getUuidOrNull();
                    if (this.playerUuid == null) {
                         System.err.println("[FogAPIClient ERROR] Cannot connect: Player UUID is still null after recheck.");
                         return;
                    }
                     if (DEBUG_MODE) System.out.println("[FogAPIClient DEBUG] Player UUID obtained for connection: " + this.playerUuid);
                } else {
                     System.err.println("[FogAPIClient ERROR] Cannot connect: Minecraft client or session not available to get UUID.");
                     return;
                }
            } catch (Exception e) {
                 System.err.println("[FogAPIClient ERROR] Error getting Player UUID for connection: " + e.getMessage());
                 return;
            }
        }


        // 이미 연결된 경우 중단
        if (isConnected()) {
             if (DEBUG_MODE) {
                 System.out.println("[FogAPIClient DEBUG] connectWebSocket called but already connected (OPEN state).");
             }
            return;
        }

        // 이전 연결 시도 정리 (연결 중이거나 닫히지 않은 상태일 때)
        if (webSocketClient != null && webSocketClient.getReadyState() != ReadyState.CLOSED) {
            if (DEBUG_MODE) {
                System.out.println("[FogAPIClient DEBUG] Previous WebSocket client instance exists and is not CLOSED. Closing it before attempting new connection. State: " + webSocketClient.getReadyState());
            }
            try {
                // closeBlocking()은 메인 스레드에서 사용 시 게임 멈춤 유발 가능 -> 비동기 close() 사용 권장
                webSocketClient.close(); // 비동기 종료 요청
                // 종료 완료를 기다리지 않고 새 인스턴스 생성으로 넘어감
                // 필요시 onClose 콜백에서 상태를 관리하여 완전히 종료된 후 재시도하는 로직 구현 가능
            } catch (Exception e) {
                 System.err.println("[FogAPIClient WARN] Exception while closing previous WebSocket connection: " + e.getMessage());
            }
            webSocketClient = null; // 이전 참조 해제
        }


         if (websocketEndpoint == null || websocketEndpoint.trim().isEmpty()) {
             System.err.println("[FogAPIClient ERROR] Cannot connect: WebSocket endpoint is not set.");
             return;
         }


        try {
            // URI 생성 시 플레이어 UUID 포함
            String uriString = websocketEndpoint + (websocketEndpoint.contains("?") ? "&" : "?") + "uuid=" + playerUuid.toString();
            URI serverUri = new URI(uriString);

            if (DEBUG_MODE) {
                System.out.println("[FogAPIClient DEBUG] Attempting to connect to WebSocket URI: " + serverUri);
            }

            // 헤더 추가 예시 (필요한 경우)
            // Map<String, String> httpHeaders = new HashMap<>();
            // httpHeaders.put("X-Custom-Header", "value");

            // 새 웹소켓 클라이언트 인스턴스 생성
            webSocketClient = new FogWebSocketClient(serverUri /*, headers, timeout 등 */);
            webSocketClient.connect(); // 비동기 연결 시작

            if (DEBUG_MODE) {
                 System.out.println("[FogAPIClient DEBUG] connect() called. Asynchronous connection initiated.");
            }

        } catch (URISyntaxException e) {
            System.err.println("[FogAPIClient ERROR] WebSocket URI Syntax Error: " + e.getMessage());
        } catch (Exception e) { // IllegalStateException 등 connect() 시 발생 가능
             System.err.println("[FogAPIClient ERROR] Exception during WebSocket connection attempt: " + e.getMessage());
             e.printStackTrace();
             webSocketClient = null; // 실패 시 참조 정리
        }
    }

    /**
     * 웹소켓 연결 종료
     */
    public void disconnectWebSocket() {
        // 닫히지 않은 상태일 때만 close 호출
        if (webSocketClient != null && webSocketClient.getReadyState() != ReadyState.CLOSED) {
            if (DEBUG_MODE) {
                System.out.println("[FogAPIClient DEBUG] Disconnecting WebSocket... Current state: " + webSocketClient.getReadyState());
            }
            try {
                webSocketClient.close(); // 비동기 종료 요청
            } catch (Exception e) {
                System.err.println("[FogAPIClient WARN] Exception during WebSocket disconnect: " + e.getMessage());
            }
            // webSocketClient = null; // onClose에서 null 처리하거나, 즉시 참조 해제 필요 시 여기서
        } else {
             if (DEBUG_MODE) {
                 System.out.println("[FogAPIClient DEBUG] disconnectWebSocket called but already closed or not initialized.");
             }
        }
    }

    /**
     * 웹소켓 클라이언트 내부 클래스
     */
    private class FogWebSocketClient extends WebSocketClient {

        public FogWebSocketClient(URI serverUri /*, Map<String, String> httpHeaders, int connectTimeout*/) {
            super(serverUri /*, new Draft_6455(), httpHeaders, connectTimeout*/); // Draft, 헤더, 타임아웃 예시
             if (DEBUG_MODE) {
                 System.out.println("[FogWebSocketClient DEBUG] Instance created for URI: " + serverUri);
             }
             // 추가 옵션 설정 예시
             // this.setConnectionLostTimeout(60); // 초 단위, 연결 유실 타임아웃 설정
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
             if (DEBUG_MODE) {
                 System.out.println("[FogWebSocketClient DEBUG] WebSocket connection opened successfully.");
                 System.out.println("  Status: " + handshakedata.getHttpStatus() + " " + handshakedata.getHttpStatusMessage());
                 // 수신된 헤더 디버깅
                 // System.out.println("  Received Headers:");
                 // handshakedata.iterateHttpFields().forEachRemaining(key ->
                 //    System.out.println("    " + key + ": " + handshakedata.getFieldValue(key))
                 // );
             }
            System.out.println("[FogOfWar] 웹소켓 서버에 연결됨");
        }

        @Override
        public void onMessage(String message) {
             if (DEBUG_MODE) {
                 // 메시지 크기가 크면 일부만 로깅
                 String logMessage = message;
                 if (logMessage != null && logMessage.length() > 500) {
                     logMessage = logMessage.substring(0, 500) + "... (truncated)";
                 }
                 System.out.println("[FogWebSocketClient DEBUG] Received raw message: " + logMessage);
             }
            if (message == null || message.trim().isEmpty()) {
                 if (DEBUG_MODE) {
                     System.out.println("[FogWebSocketClient DEBUG] Received empty message, ignoring.");
                 }
                 return;
            }

            try {
                JsonObject jsonData = GSON.fromJson(message, JsonObject.class);
                
                // NPC UUID 정보 처리
                if (jsonData.has("npcUuids")) {
                    if (DEBUG_MODE) {
                        System.out.println("[FogWebSocketClient DEBUG] NPC UUID 정보 발견, NPCManager로 전달합니다.");
                    }
                    NPCManager npcManager = NPCManager.getInstance();
                    int processedCount = npcManager.processNpcUuidsFromJson(jsonData);
                    if (DEBUG_MODE) {
                        System.out.println("[FogWebSocketClient DEBUG] " + processedCount + "개의 NPC UUID가 처리되었습니다.");
                    }
                }
                
                // 안개 데이터 처리 (기존 로직)
                if (dataConsumer != null) {
                    if (DEBUG_MODE) {
                        System.out.println("[FogWebSocketClient DEBUG] Parsed JSON, passing to data consumer.");
                    }
                    // 데이터 처리는 FogOfWarClient의 updateFromApiResponse에서 메인 스레드로 예약됨
                    dataConsumer.accept(jsonData);
                } else {
                    if (DEBUG_MODE) {
                        System.out.println("[FogWebSocketClient WARN] Received message but data consumer is null.");
                    }
                }
            } catch (com.google.gson.JsonSyntaxException e) {
                 System.err.println("[FogWebSocketClient ERROR] JSON Syntax Error processing message: " + e.getMessage());
                 if (DEBUG_MODE) {
                     String logMessage = message;
                     if (logMessage != null && logMessage.length() > 500) {
                         logMessage = logMessage.substring(0, 500) + "... (truncated)";
                     }
                     System.err.println("  Invalid JSON content: " + logMessage);
                 }
            } catch (Exception e) {
                System.err.println("[FogWebSocketClient ERROR] Unexpected error processing WebSocket message: " + e.getMessage());
                if (DEBUG_MODE) {
                     e.printStackTrace();
                }
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
             if (DEBUG_MODE) {
                 System.out.println("[FogWebSocketClient DEBUG] WebSocket connection closed.");
                 System.out.println("  Code: " + code);
                 System.out.println("  Reason: " + (reason == null || reason.isEmpty() ? "N/A" : reason));
                 System.out.println("  Closed by remote: " + remote);
             }
             String closeReason = reason == null || reason.isEmpty() ? "(No specific reason provided)" : reason;
             System.out.println("[FogOfWar] 웹소켓 연결 종료됨 (Code: " + code + ", Reason: " + closeReason + ", Remote: " + remote + ")");

             // 클라이언트 참조 해제 (중요: 재연결 로직과 연관)
             // 이 인스턴스가 현재 활성 인스턴스인지 확인 후 null 처리
             if (FogAPIClient.this.webSocketClient == this) {
                 FogAPIClient.this.webSocketClient = null;
                 if (DEBUG_MODE) System.out.println("[FogWebSocketClient DEBUG] Cleared FogAPIClient's webSocketClient reference.");
             }
             // 여기에 재연결 로직 추가 가능 (예: 특정 코드가 아닐 경우 지연 후 connectWebSocket 호출)
             // if (!remote && code != 1000) { // 예: 정상 종료(1000) 또는 서버에 의한 종료가 아닐 때
             //     new Thread(() -> { try { Thread.sleep(5000); connectWebSocket(); } catch (InterruptedException ignored) {} }).start();
             // }
        }

        @Override
        public void onError(Exception ex) {
            System.err.println("[FogWebSocketClient ERROR] WebSocket error occurred: " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
            if (DEBUG_MODE) {
                 ex.printStackTrace();
            }
            // 오류 발생 시 onClose가 항상 호출되는 것은 아니므로,
            // 여기서도 webSocketClient 참조를 null로 설정하는 것을 고려할 수 있음
            // 하지만, 오류 후에도 close() 시도가 필요할 수 있으므로 onClose에서 처리하는 것이 일반적
        }
    }

    /**
     * 청크 리프레시 요청을 보냅니다.
     * 변경된 청크 좌표를 서버에 전송하여 새로 고침을 요청합니다.
     * 
     * @param chunks 리프레시할 청크 좌표 목록
     */
    public void requestChunkRefresh(Set<? extends Object> chunks) {
        if (!isConnected()) {
            if (DEBUG_MODE) {
                System.out.println("[FogAPIClient DEBUG] 청크 리프레시 요청 실패: 웹소켓 연결 없음");
            }
            return;
        }
        
        if (chunks == null || chunks.isEmpty()) {
            if (DEBUG_MODE) {
                System.out.println("[FogAPIClient DEBUG] 청크 리프레시 요청 실패: 빈 청크 목록");
            }
            return;
        }
        




        
        // try {
        //     // JSON 데이터 생성
        //     JsonObject jsonRequest = new JsonObject();
        //     jsonRequest.addProperty("type", "chunk_refresh");
            
        //     // 플레이어 UUID 추가
        //     if (playerUuid != null) {
        //         jsonRequest.addProperty("uuid", playerUuid.toString());
        //     }
            
        //     // 청크 좌표 추가
        //     JsonArray chunksArray = new JsonArray();
        //     for (Object chunk : chunks) {
        //         JsonObject chunkObj = new JsonObject();
                
        //         // 청크 객체 형식에 따라 좌표 추출
        //         if (chunk instanceof net.civarmymod.NPCChunkManager.ChunkPosition) {
        //             net.civarmymod.NPCChunkManager.ChunkPosition pos = (net.civarmymod.NPCChunkManager.ChunkPosition) chunk;
        //             chunkObj.addProperty("x", pos.x);
        //             chunkObj.addProperty("z", pos.z);
        //         } else if (chunk instanceof net.civarmymod.FogOfWarClient.ChunkPosition) {
        //             net.civarmymod.FogOfWarClient.ChunkPosition pos = (net.civarmymod.FogOfWarClient.ChunkPosition) chunk;
        //             chunkObj.addProperty("x", pos.x);
        //             chunkObj.addProperty("z", pos.z);
        //         } else if (chunk instanceof net.minecraft.util.math.ChunkPos) {
        //             net.minecraft.util.math.ChunkPos pos = (net.minecraft.util.math.ChunkPos) chunk;
        //             chunkObj.addProperty("x", pos.x);
        //             chunkObj.addProperty("z", pos.z);
        //         } else {
        //             // 기타 형식은 무시
        //             continue;
        //         }
                
        //         chunksArray.add(chunkObj);
        //     }
            
        //     jsonRequest.add("chunks", chunksArray);
            
        //     // 웹소켓으로 전송
        //     String jsonString = GSON.toJson(jsonRequest);
        //     webSocketClient.send(jsonString);
            
        //     if (DEBUG_MODE) {
        //         System.out.println("[FogAPIClient DEBUG] 청크 리프레시 요청 전송: " + chunksArray.size() + "개 청크");
        //     }
            
        // } catch (Exception e) {
        //     System.err.println("[FogAPIClient ERROR] 청크 리프레시 요청 중 오류: " + e.getMessage());
        //     if (DEBUG_MODE) {
        //         e.printStackTrace();
        //     }
        // }
    }
    
    /**
     * Base64로 인코딩된 스냅샷 데이터를 디코딩
     * JSON 형식과 NBT 형식 모두 지원
     */
    public static NbtCompound decodeSnapshot(String base64Encoded) {
        if (base64Encoded == null || base64Encoded.isEmpty()) {
             if (DEBUG_MODE) {
                 System.out.println("[FogAPIClient DEBUG] decodeSnapshot called with null or empty string.");
             }
            return null;
        }

        try {
             if (DEBUG_MODE) {
                 String logData = base64Encoded;
                 if (logData.length() > 100) logData = logData.substring(0, 100) + "...";
                 System.out.println("[FogAPIClient DEBUG] Decoding Base64 snapshot data (length: " + base64Encoded.length() + "): " + logData);
             }
            
            // Base64 디코딩
            byte[] decodedBytes = Base64.getDecoder().decode(base64Encoded);
            if (DEBUG_MODE) {
                 System.out.println("[FogAPIClient DEBUG] Decoded to " + decodedBytes.length + " bytes.");
            }
            
            // 일반 텍스트로 보고 JSON 형식인지 확인
            String jsonStr = new String(decodedBytes, "UTF-8");
            if (DEBUG_MODE) {
                String logJson = jsonStr;
                if (logJson.length() > 100) logJson = logJson.substring(0, 100) + "...";
                System.out.println("[FogAPIClient DEBUG] Decoded text: " + logJson);
            }
            
            // JSON 형식인지 확인
            if (jsonStr.trim().startsWith("{") && jsonStr.trim().endsWith("}")) {
                try {
                    if (DEBUG_MODE) {
                        System.out.println("[FogAPIClient DEBUG] Detected JSON format, parsing...");
                    }
                    
                    // JSON 파싱
                    NbtCompound jsonNbt = new NbtCompound();
                    com.google.gson.JsonObject jsonObj = new com.google.gson.JsonParser().parse(jsonStr).getAsJsonObject();
                    
                    // JSON 객체의 각 키-값 쌍을 NBT로 변환
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : jsonObj.entrySet()) {
                        String key = entry.getKey();
                        if (entry.getValue().isJsonPrimitive()) {
                            jsonNbt.putString(key, entry.getValue().getAsString());
                        }
                    }
                    
                    if (DEBUG_MODE) {
                        System.out.println("[FogAPIClient DEBUG] JSON decoded successfully to NBT with " + jsonNbt.getSize() + " entries.");
                    }
                    return jsonNbt;
                } catch (Exception e) {
                    System.err.println("[FogAPIClient ERROR] JSON parsing failed: " + e.getMessage());
                    if (DEBUG_MODE) {
                        e.printStackTrace();
                    }
                    return null;
                }
            } else {
                // JSON 형식이 아니면 NBT 형식으로 시도
                try {
                    if (DEBUG_MODE) {
                        System.out.println("[FogAPIClient DEBUG] Not a JSON format, trying NBT format...");
                    }
                    
                    // 압축되지 않은 NBT 형식 시도
                    try {
                        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(decodedBytes));
                        NbtElement nbtElement = NbtIo.read(dis, NbtSizeTracker.ofUnlimitedBytes());
                        if (nbtElement instanceof NbtCompound) {
                            NbtCompound decodedNbt = (NbtCompound)nbtElement;
                            if (DEBUG_MODE) {
                                System.out.println("[FogAPIClient DEBUG] Uncompressed NBT decoded successfully.");
                            }
                            return decodedNbt;
                        } else {
                            throw new Exception("Decoded NBT is not a compound: " + nbtElement.getClass().getSimpleName());
                        }
                    } catch (Exception uncompressedEx) {
                        if (DEBUG_MODE) {
                            System.out.println("[FogAPIClient DEBUG] Not an uncompressed NBT, trying compressed NBT...");
                        }
                        
                        // 압축된 NBT 형식 시도
                        ByteArrayInputStream bais = new ByteArrayInputStream(decodedBytes);
                        NbtCompound decodedNbt = NbtIo.readCompressed(bais, NbtSizeTracker.ofUnlimitedBytes());
                        if (DEBUG_MODE) {
                            System.out.println("[FogAPIClient DEBUG] Compressed NBT decoded successfully.");
                        }
                        return decodedNbt;
                    }
                } catch (Exception e) {
                    System.err.println("[FogAPIClient ERROR] NBT parsing failed: " + e.getMessage());
                    if (DEBUG_MODE) {
                        e.printStackTrace();
                    }
                    return null;
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("[FogAPIClient ERROR] Base64 decoding failed: " + e.getMessage());
            if (DEBUG_MODE) {
                 String logData = base64Encoded;
                 if (logData.length() > 100) logData = logData.substring(0, 100) + "...";
                 System.err.println("  Invalid Base64 data (length: " + base64Encoded.length() + "): " + logData);
            }
            return null;
        } catch (Exception e) {
            System.err.println("[FogAPIClient ERROR] Snapshot decoding failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
             if (DEBUG_MODE) {
                 e.printStackTrace();
             }
            return null;
        }
    }
}