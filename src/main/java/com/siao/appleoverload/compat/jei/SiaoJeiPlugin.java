package com.siao.appleoverload.compat.jei;

import com.siao.appleoverload.SiaoMod;
import com.siao.appleoverload.content.SiaoBlocks;
import com.siao.appleoverload.machine.MachineKind;
import com.siao.appleoverload.recipe.SiaoCompressionRecipe;
import com.siao.appleoverload.recipe.SiaoRecipeTypes;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.List;

/**
 * JEI 插件。
 *
 * <p>压缩链用的是自定义 {@link SiaoRecipeTypes#COMPRESSION}，JEI 不会自动认识，
 * 所以这里给它一个独立分类：左边摆原料、右边摆产物、压缩机作为催化剂。</p>
 *
 * <p>JEI 是**可选**的：本类只在 JEI 存在时被它的注解扫描加载，
 * 没装 JEI 时永远不会被触碰，所以主模组不需要 hard dependency。</p>
 */
@JeiPlugin
public class SiaoJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(SiaoMod.MOD_ID, "jei_plugin");

    public static final RecipeType<SiaoCompressionRecipe> COMPRESSION =
            RecipeType.create(SiaoMod.MOD_ID, "compression", SiaoCompressionRecipe.class);

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new CompressionCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        List<SiaoCompressionRecipe> recipes = server.getRecipeManager()
                .getAllRecipesFor(SiaoRecipeTypes.COMPRESSION.get()).stream()
                .map(RecipeHolder::value)
                .toList();
        registration.addRecipes(COMPRESSION, recipes);
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(
                new ItemStack(SiaoBlocks.machine(MachineKind.COMPRESSOR).get()), COMPRESSION);
    }

    /** 「压缩」分类：原料 → 产物。 */
    private static final class CompressionCategory implements IRecipeCategory<SiaoCompressionRecipe> {

        private final IDrawable background;
        private final IDrawable icon;

        private CompressionCategory(IGuiHelper guiHelper) {
            this.background = guiHelper.createBlankDrawable(120, 36);
            this.icon = guiHelper.createDrawableItemStack(
                    new ItemStack(SiaoBlocks.machine(MachineKind.COMPRESSOR).get()));
        }

        @Override
        public RecipeType<SiaoCompressionRecipe> getRecipeType() {
            return COMPRESSION;
        }

        @Override
        public Component getTitle() {
            return Component.translatable("jei.siao.compression");
        }

        @Override
        public IDrawable getBackground() {
            return background;
        }

        @Override
        public IDrawable getIcon() {
            return icon;
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, SiaoCompressionRecipe recipe, IFocusGroup focuses) {
            builder.addSlot(mezz.jei.api.recipe.RecipeIngredientRole.INPUT, 1, 10)
                    .addItemStacks(List.of(recipe.ingredient().getItems()))
                    .setSlotName("input");
            builder.addSlot(mezz.jei.api.recipe.RecipeIngredientRole.OUTPUT, 101, 10)
                    .addItemStack(recipe.result().copy())
                    .setSlotName("output");
        }
    }
}
