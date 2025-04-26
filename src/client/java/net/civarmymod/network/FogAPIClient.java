package net.civarmymod.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 서버 API와 통신하는 클라이언트
 * 웹소켓을 통해 안개 데이터를 받아옵니다.
 */
@Environment(EnvType.CLIENT)
public class FogAPIClient {
    private static final Gson GSON = new GsonBuilder().create();
    
    private String websocketEndpoint = "ws://localhost:8080/api/fog/ws";
    private UUID playerUuid;
    private FogWebSocketClient webSocketClient;
    private Consumer<JsonObject> dataConsumer;
    
    public FogAPIClient() {
        this.playerUuid = MinecraftClient.getInstance().getSession().getUuidOrNull();
    }
    
    /**
     * API 엔드포인트 설정
     */
    public void setApiEndpoint(String endpoint) {
        if (endpoint.startsWith("http")) {
            // HTTP -> WebSocket 변환
            this.websocketEndpoint = endpoint.replace("http", "ws") + "/ws";
        } else if (endpoint.startsWith("ws")) {
            this.websocketEndpoint = endpoint;
        } else {
            this.websocketEndpoint = "ws://" + endpoint + "/ws";
        }
        
        // 이미 연결되어 있다면 재연결
        if (webSocketClient != null && webSocketClient.isOpen()) {
            webSocketClient.close();
            connectWebSocket();
        }
    }
    
    /**
     * 웹소켓 엔드포인트 설정
     */
    public void setWebsocketEndpoint(String endpoint) {
        if (endpoint.startsWith("http")) {
            // HTTP -> WebSocket 변환
            this.websocketEndpoint = endpoint.replace("http", "ws") + "/ws";
        } else if (endpoint.startsWith("ws")) {
            this.websocketEndpoint = endpoint;
        } else {
            this.websocketEndpoint = "ws://" + endpoint + "/ws";
        }
        
        // 이미 연결되어 있다면 재연결
        if (webSocketClient != null && webSocketClient.isOpen()) {
            webSocketClient.close();
            connectWebSocket();
        }
    }
    
    /**
     * 데이터 수신 핸들러 설정
     */
    public void setDataConsumer(Consumer<JsonObject> consumer) {
        this.dataConsumer = consumer;
    }
    
    /**
     * 웹소켓 연결 상태 확인
     */
    public boolean isConnected() {
        return webSocketClient != null && webSocketClient.isOpen();
    }
    
    /**
     * 웹소켓 연결 시작
     */
    public void connectWebSocket() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            return; // 이미 연결됨
        }
        
        try {
            URI serverUri = new URI(websocketEndpoint + "?uuid=" + playerUuid.toString());
            webSocketClient = new FogWebSocketClient(serverUri);
            webSocketClient.connect();
        } catch (URISyntaxException e) {
            System.err.println("웹소켓 URI 형식 오류: " + e.getMessage());
        }
    }
    
    /**
     * 웹소켓 연결 종료
     */
    public void disconnectWebSocket() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            webSocketClient.close();
        }
    }
    
    /**
     * 웹소켓 클라이언트 내부 클래스
     */
    private class FogWebSocketClient extends WebSocketClient {
        
        public FogWebSocketClient(URI serverUri) {
            super(serverUri);
        }
        
        @Override
        public void onOpen(ServerHandshake handshakedata) {
            System.out.println("웹소켓 서버에 연결됨");
        }
        
        @Override
        public void onMessage(String message) {
            try {
                JsonObject jsonData = GSON.fromJson(message, JsonObject.class);
                if (dataConsumer != null) {
                    dataConsumer.accept(jsonData);
                }
            } catch (Exception e) {
                System.err.println("웹소켓 메시지 처리 오류: " + e.getMessage());
            }
        }
        
        @Override
        public void onClose(int code, String reason, boolean remote) {
            System.out.println("웹소켓 연결 종료: " + reason);
        }
        
        @Override
        public void onError(Exception ex) {
            System.err.println("웹소켓 오류: " + ex.getMessage());
        }
    }
    
    /**
     * Base64로 인코딩된 NBT 데이터를 디코딩
     */
    public static NbtCompound decodeSnapshot(String base64Encoded) {
        try {
            byte[] nbtBytes = Base64.getDecoder().decode(base64Encoded);
            return NbtIo.readCompressed(new ByteArrayInputStream(nbtBytes), NbtSizeTracker.ofUnlimitedBytes());
        } catch (Exception e) {
            System.err.println("NBT 디코딩 오류: " + e.getMessage());
            return null;
        }
    }
}
