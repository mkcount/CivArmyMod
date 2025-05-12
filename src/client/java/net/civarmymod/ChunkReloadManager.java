package net.civarmymod;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.dimension.DimensionType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ChunkReloadManager {

    private static final Logger LOGGER = LogManager.getLogger("CivArmyMod/ChunkReloadManager");

    /**
     * Sodium 렌더러를 사용하여 특정 청크 열 (모든 수직 섹션 포함)의 리빌드를 요청합니다.
     * 이 메서드는 Sodium 모드가 로드되었을 때만 호출되어야 합니다.
     *
     * @param chunkX 리빌드할 청크의 X 좌표
     * @param chunkZ 리빌드할 청크의 Z 좌표
     */
    public static void requestChunkReload(int chunkX, int chunkZ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) {
            LOGGER.error("청크 리로드 실패: MinecraftClient 또는 ClientWorld가 null입니다. (ChunkX: {}, ChunkZ: {})", chunkX, chunkZ);
            return;
        }

        SodiumWorldRenderer sodiumRenderer = SodiumWorldRenderer.instanceNullable();
        if (sodiumRenderer == null) {
            LOGGER.error("Sodium 청크 리로드 실패: SodiumWorldRenderer 인스턴스를 가져올 수 없습니다. (ChunkX: {}, ChunkZ: {})", chunkX, chunkZ);
            return;
        }

        ClientWorld world = client.world;
        DimensionType dimensionType = world.getDimension();
        int minBuildHeight = dimensionType.minY();
        int maxBuildHeight = dimensionType.minY() + dimensionType.height();

        LOGGER.info("DimensionType Info: minY={}, height={}, logicalHeight={}", dimensionType.minY(), dimensionType.height(), dimensionType.logicalHeight());
        LOGGER.info("Calculated Build Heights: minBuildHeight={}, maxBuildHeight={}", minBuildHeight, maxBuildHeight);

        int minSectionY = minBuildHeight >> 4; // minBuildHeight / 16과 동일 (섹션 인덱스)
        int maxSectionY = (maxBuildHeight -1) >> 4; // (maxBuildHeight - 1) / 16과 동일 (섹션 인덱스)

        LOGGER.info("Sodium: 청크 ({}, {})의 리빌드 요청 시작. MinSectionY: {}, MaxSectionY: {}", chunkX, chunkZ, minSectionY, maxSectionY);

        for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
            // SodiumWorldRenderer.scheduleRebuildForChunk는 청크 섹션 좌표를 사용합니다.
            // (chunkX, sectionY, chunkZ)
            sodiumRenderer.scheduleRebuildForChunk(chunkX, sectionY, chunkZ, true); // true는 'important' 플래그입니다.
            LOGGER.info("Sodium: 청크 ({}, {}), 섹션 Y: {} 리빌드 스케줄됨.", chunkX, chunkZ, sectionY);
        }
        LOGGER.info("Sodium: 청크 ({}, {})에 대한 모든 섹션 리빌드 요청 완료.", chunkX, chunkZ);
    }
}