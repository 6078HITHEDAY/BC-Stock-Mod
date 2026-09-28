package cn.myflycat.bcstock.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/**
 * ModMenu 入口。Cloth Config 为可选依赖：没装时返回 {@code null}（无设置按钮），
 * 不崩、不影响其它功能。
 */
public final class BcStockModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
                return null;
            }
            return BcStockClothConfig.create(parent);
        };
    }
}
