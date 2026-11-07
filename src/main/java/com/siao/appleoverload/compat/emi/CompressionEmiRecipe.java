package com.siao.appleoverload.compat.emi;

import com.siao.appleoverload.recipe.SiaoCompressionRecipe;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * EMI 里的一条「压缩」配方。
 *
 * <p>⚠️ 升重和装块的<b>输入完全一样</b>（都是 9 个同一物品），EMI 会把它们当成
 * 同一条配方，结果装块配方在 EMI 里查不到。这里把装块类的输入摊成 9 个独立槽位
 * （3×3 网格），输入签名就不同了，两条都能显示出来。</p>
 */
public class CompressionEmiRecipe implements EmiRecipe {

    private final RecipeHolder<?> holder;
    private final SiaoCompressionRecipe recipe;
    /** 输入摊成 3×3 网格（装块类配方用，避免和升重配方被当成同一条）。 */
    private final boolean gridInputs;

    public CompressionEmiRecipe(RecipeHolder<?> holder) {
        this.holder = holder;
        this.recipe = (SiaoCompressionRecipe) holder.value();
        this.gridInputs = recipe.result().getItem() instanceof BlockItem && recipe.count() == 9;
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
        if (!gridInputs) {
            return List.of(EmiIngredient.of(recipe.ingredient()).setAmount(recipe.count()));
        }
        List<EmiIngredient> inputs = new ArrayList<>(9);
        for (int i = 0; i < recipe.count(); i++) {
            inputs.add(EmiIngredient.of(recipe.ingredient()));
        }
        return inputs;
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
        return gridInputs ? 58 : 26;
    }

    @Override
    public void addWidgets(WidgetHolder widgets) {
        List<EmiIngredient> inputs = getInputs();
        if (gridInputs) {
            for (int i = 0; i < inputs.size(); i++) {
                widgets.addSlot(inputs.get(i), 1 + (i % 3) * 18, 1 + (i / 3) * 18);
            }
            widgets.addSlot(getOutputs().get(0), 94, 4);
        } else {
            widgets.addSlot(inputs.get(0), 4, 4);
            widgets.addSlot(getOutputs().get(0), 94, 4);
        }
    }
}
