package com.siao.appleoverload.compat.emi;

import com.siao.appleoverload.recipe.SiaoCompressionRecipe;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.List;

/** EMI 里的一条「压缩」配方：左边原料（带数量），右边产物。 */
public class CompressionEmiRecipe implements EmiRecipe {

    private final RecipeHolder<?> holder;
    private final SiaoCompressionRecipe recipe;

    public CompressionEmiRecipe(RecipeHolder<?> holder) {
        this.holder = holder;
        this.recipe = (SiaoCompressionRecipe) holder.value();
    }

    @Override
    public EmiRecipeCategory getCategory() {
        return SiaoEmiPlugin.COMPRESSION;
    }

    @Override
    public ResourceLocation getId() {
        return holder.id();
    }

    @Override
    public List<EmiIngredient> getInputs() {
        return List.of(EmiIngredient.of(recipe.ingredient()).setAmount(recipe.count()));
    }

    @Override
    public List<EmiStack> getOutputs() {
        return List.of(EmiStack.of(recipe.result()));
    }

    @Override
    public int getDisplayWidth() {
        return 118;
    }

    @Override
    public int getDisplayHeight() {
        return 26;
    }

    @Override
    public void addWidgets(WidgetHolder widgets) {
        widgets.addSlot(getInputs().get(0), 4, 4);
        widgets.addSlot(getOutputs().get(0), 94, 4);
    }
}
