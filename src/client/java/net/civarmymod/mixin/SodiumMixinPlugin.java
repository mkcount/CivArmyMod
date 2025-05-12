package net.civarmymod.mixin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class SodiumMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger("CivArmyMod/SodiumCompat");

    private static final boolean SODIUM_LOADED;

    static {
        boolean sodiumLoaded = false;
        try {
            Class.forName("net.caffeinemc.mods.sodium.client.SodiumClientMod");
            sodiumLoaded = true;
            LOGGER.info("소듐 모드가 감지되었습니다. 소듐 호환 Mixin을 적용합니다.");
        } catch (ClassNotFoundException e) {
            LOGGER.info("소듐 모드가 설치되지 않았습니다. 소듐 호환 Mixin을 적용하지 않습니다.");
        }
        SODIUM_LOADED = sodiumLoaded;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        LOGGER.info("[DiamondMod] shouldApplyMixin 호출됨: " + targetClassName + " -> " + mixinClassName + ", 결과=" + SODIUM_LOADED);
        return SODIUM_LOADED;
    }

    @Override
    public void onLoad(String mixinPackage) {
        LOGGER.info("[DiamondMod] 소듐 Mixin 플러그인 onLoad 호출됨: " + mixinPackage + ", SODIUM_LOADED=" + SODIUM_LOADED);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
