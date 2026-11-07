package com.siao.appleoverload.compat.emi;

import com.siao.appleoverload.SiaoMod;
import com.siao.appleoverload.content.SiaoBlocks;
import com.siao.appleoverload.machine.MachineKind;
import com.siao.appleoverload.recipe.SiaoRecipeTypes;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * EMI 插件：把自定义的 {@code siao:compression} 配方加进 EMI。
 *
 * <p>EMI 自己不认识自定义配方类型，所以压缩链在 EMI 里是查不到的 ——
 * 这个插件补上「压缩」分类，催化剂是压缩机。</p>
 *
 * <p>和 JEI 插件一样是**可选依赖**：没装 EMI 时这个类不会被加载。</p>
 */
@EmiEntrypoint
public class SiaoEmiPlugin implements EmiPlugin {

    public static final EmiRecipeCategory COMPRESSION = new EmiRecipeCategory(
            ResourceLocation.fromNamespaceAndPath(SiaoMod.MOD_ID, "compression"),
            EmiStack.of(SiaoBlocks.machine(MachineKind.COMPRESSOR).get()));

    @Override
    public void register(EmiRegistry registry) {
        registry.addCategory(COMPRESSION);
        registry.addWorkstation(COMPRESSION,
                EmiStack.of(SiaoBlocks.machine(MachineKind.COMPRESSOR).get()));

        int count = 0;
        for (RecipeHolder<?> holder : registry.getRecipeManager()
                .getAllRecipesFor(SiaoRecipeTypes.COMPRESSION.get())) {
            registry.addRecipe(new CompressionEmiRecipe(holder));
            count++;
        }
        SiaoMod.LOGGER.info("[SIAO-EMI] 注册 {} 条压缩配方到 EMI", count);
    }
}
