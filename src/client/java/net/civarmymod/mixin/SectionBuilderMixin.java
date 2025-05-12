// package net.civarmymod.mixin;

// import net.minecraft.block.BlockState;
// import net.minecraft.block.Blocks;
// import net.minecraft.client.render.chunk.ChunkRendererRegion;
// import net.minecraft.client.render.chunk.SectionBuilder;
// import net.minecraft.util.math.BlockPos;
// import org.spongepowered.asm.mixin.Mixin;
// import org.spongepowered.asm.mixin.injection.At;
// import org.spongepowered.asm.mixin.injection.Redirect;

// @Mixin(SectionBuilder.class)
// public class SectionBuilderMixin {
    
//     /**
//      * SectionBuilder.build 메서드 내에서 getBlockState 호출을 가로채서 다이아몬드 블록으로 변경
//      */
//     @Redirect(method = "build", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/chunk/ChunkRendererRegion;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"))
//     private BlockState redirectGetBlockState(ChunkRendererRegion renderRegion, BlockPos pos) {
//         // 디버깅 코드
//         System.out.println("[DiamondMod] getBlockState 호출됨: " + pos);
        
//         // 원래 블록 상태
//         BlockState originalState = renderRegion.getBlockState(pos);
//         System.out.println("[DiamondMod] 원래 블록: " + originalState.getBlock().getTranslationKey());
        
//         // 모든 블록을 다이아몬드 블록으로 변경
//         System.out.println("[DiamondMod] 블록을 다이아몬드로 변경: " + pos);
//         return Blocks.DIAMOND_BLOCK.getDefaultState();
        
//         // 특정 청크만 변경하려면 아래와 같이 조건 추가
//         /*
//         int chunkX = pos.getX() >> 4;
//         int chunkZ = pos.getZ() >> 4;
        
//         if (chunkX == 0 && chunkZ == 0) {
//             System.out.println("[DiamondMod] 청크 (0,0)의 블록을 다이아몬드로 변경: " + pos);
//             return Blocks.DIAMOND_BLOCK.getDefaultState();
//         }
        
//         return originalState;
//         */
//     }
// }
