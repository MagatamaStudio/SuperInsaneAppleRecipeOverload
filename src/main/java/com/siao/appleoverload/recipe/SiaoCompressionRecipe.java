package com.siao.appleoverload.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.siao.appleoverload.config.SiaoConfig;
import com.siao.appleoverload.content.CompressionInfo;
import com.siao.appleoverload.content.Material;
import com.siao.appleoverload.content.SiaoComponents;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * 苹果压缩配方：<b>N 个同种物品 → M 个产物</b>，输出在 {@code assemble} 里现算。
 *
 * <p>为什么不用 {@code crafting_shaped} / {@code crafting_shapeless}：
 * 原版配方类的产物是写死的 {@code ItemStack}，而附魔是数据组件，
 * 「9 个附魔 → 1 个附魔」必须<b>读输入栈</b>才能决定输出。原版
 * {@link net.minecraft.world.item.crafting.Recipe#getResultItem} 拿不到输入，
 * 只有 {@link #assemble} 可以，所以只能自定义配方类。</p>
 *
 * <p>同时它顺带解决了别的配方类解决不了的两件事：</p>
 * <ol>
 *   <li><b>混合输入拒绝</b>：9 个里只要附魔状态不一致就不匹配，
 *       避免「8 个普通 + 1 个附魔」被某个配方偷偷吃掉附魔。</li>
 *   <li><b>附魔不可下泄</b>：拆分 / 装块 / 拆块三种配方用 {@link EnchantMode#REJECT}，
 *       附魔物品匹配不上，防止「1 个附魔拆成 9 个附魔」的复制漏洞。</li>
 * </ol>
 *
 * <p>配方 ID 与之前的原版配方完全一致，json 结构如下：</p>
 * <pre>{@code
 * {
 *   "type": "siao:compression",
 *   "group": "compressed_iron",
 *   "category": "misc",
 *   "ingredient": { "item": "minecraft:iron_ingot" },
 *   "result": { "id": "siao:compressed_1x_iron", "count": 1 },
 *   "count": 9,
 *   "enchant": "preserve"
 * }
 * }</pre>
 */
public record SiaoCompressionRecipe(
        String group,
        CraftingBookCategory category,
        Ingredient ingredient,
        ItemStack result,
        int count,
        EnchantMode enchantMode) implements Recipe<CraftingInput> {

    /** 合成格最多 9 格，输入数量上限。 */
    public static final int MAX_COUNT = 9;

    public static final MapCodec<SiaoCompressionRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.optionalFieldOf("group", "").forGetter(SiaoCompressionRecipe::group),
            CraftingBookCategory.CODEC.optionalFieldOf("category", CraftingBookCategory.MISC)
                    .forGetter(SiaoCompressionRecipe::category),
            Ingredient.CODEC_NONEMPTY.fieldOf("ingredient").forGetter(SiaoCompressionRecipe::ingredient),
            ItemStack.STRICT_CODEC.fieldOf("result").forGetter(SiaoCompressionRecipe::result),
            Codec.intRange(1, MAX_COUNT).optionalFieldOf("count", MAX_COUNT)
                    .forGetter(SiaoCompressionRecipe::count),
            EnchantMode.CODEC.optionalFieldOf("enchant", EnchantMode.REJECT)
                    .forGetter(SiaoCompressionRecipe::enchantMode))
            .apply(instance, SiaoCompressionRecipe::new));

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SiaoRecipes.COMPRESSION.get();
    }

    /**
     * ⚠️ 返回的是**自定义**配方类型，不是 {@code RecipeType.CRAFTING}。
     * 这一行就是「不能手搓」的全部实现：工作台只查 CRAFTING 类型，永远看不到这些配方。
     */
    @Override
    public RecipeType<?> getType() {
        return SiaoRecipeTypes.COMPRESSION.get();
    }

    @Override
    public String getGroup() {
        return group;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        if (exceedsTierCap()) {
            return false;
        }
        int found = 0;
        Boolean uniformEnchant = null;

        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            // 多于 count 个物品：不是给这条配方准备的
            if (found >= count) {
                return false;
            }
            if (!ingredient.test(stack)) {
                return false;
            }

            boolean enchanted = SiaoComponents.isEnchanted(stack);
            if (enchantMode == EnchantMode.REJECT && enchanted) {
                return false;
            }
            if (enchantMode == EnchantMode.REQUIRE && !enchanted) {
                return false;
            }
            // 混合输入拒绝：9 个格子里附魔状态必须一致
            if (uniformEnchant == null) {
                uniformEnchant = enchanted;
            } else if (uniformEnchant != enchanted) {
                return false;
            }

            found++;
        }

        return found == count;
    }

    /**
     * 重数上限（配置项 {@code maxTier}）。
     *
     * <p>低于 9 时，超过上限的压缩配方**在配方层就不匹配** ——
     * 所以手搓和机器会同时失效，不会出现「机器不认但工作台能压」的裂缝。</p>
     */
    private boolean exceedsTierCap() {
        int cap = SiaoConfig.maxTier();
        if (cap >= Material.MAX_TIER) {
            return false;
        }
        return CompressionInfo.parse(BuiltInRegistries.ITEM.getKey(result.getItem()))
                .map(info -> info.tier() > cap)
                .orElse(false);
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack output = result.copy();
        if (enchantMode == EnchantMode.PRESERVE) {
            for (int i = 0; i < input.size(); i++) {
                ItemStack stack = input.getItem(i);
                if (!stack.isEmpty()) {
                    SiaoComponents.copyEnchantment(stack, output);
                    break;
                }
            }
        }
        return output;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= count;
    }

    /** 只看这个：JEI / EMI / 配方书展示的产物（永远是不带附魔的那个）。 */
    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return result.copy();
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        return NonNullList.withSize(count, ingredient);
    }

    /** 附魔处理方式。 */
    public enum EnchantMode implements StringRepresentable {
        /** 保留输入的附魔状态（压缩用）。 */
        PRESERVE("preserve"),
        /** 附魔输入直接不匹配（拆分 / 装块用，堵复制漏洞）。 */
        REJECT("reject"),
        /** 必须附魔才匹配，产物不带附魔（终极木棍用）。 */
        REQUIRE("require");

        public static final Codec<EnchantMode> CODEC = StringRepresentable.fromEnum(EnchantMode::values);

        private final String name;

        EnchantMode(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final class Serializer implements RecipeSerializer<SiaoCompressionRecipe> {

        /** 手写而非 {@code StreamCodec.composite}：字段多，手写少一层类型推断的坑。 */
        public static final StreamCodec<RegistryFriendlyByteBuf, SiaoCompressionRecipe> STREAM_CODEC = StreamCodec.of(
                (buffer, recipe) -> {
                    buffer.writeUtf(recipe.group);
                    buffer.writeEnum(recipe.category);
                    Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, recipe.ingredient);
                    ItemStack.STREAM_CODEC.encode(buffer, recipe.result);
                    buffer.writeVarInt(recipe.count);
                    buffer.writeEnum(recipe.enchantMode);
                },
                buffer -> new SiaoCompressionRecipe(
                        buffer.readUtf(),
                        buffer.readEnum(CraftingBookCategory.class),
                        Ingredient.CONTENTS_STREAM_CODEC.decode(buffer),
                        ItemStack.STREAM_CODEC.decode(buffer),
                        buffer.readVarInt(),
                        buffer.readEnum(EnchantMode.class)));

        @Override
        public MapCodec<SiaoCompressionRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, SiaoCompressionRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
