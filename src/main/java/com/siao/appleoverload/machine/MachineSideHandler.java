package com.siao.appleoverload.machine;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 把机器内部物品栏的**一部分槽位**以特定通行规则暴露出去。
 *
 * <p>侧配置的实现就是这一层薄包装：不改内部存储，只改某个面看到的视图。
 * 所以机器自己的加工逻辑（直接读内部 {@code ItemStackHandler}）永远不受侧配置影响，
 * 不会出现「把面全关了就卡死自己」的情况。</p>
 */
final class MachineSideHandler implements IItemHandler {

    private final IItemHandler delegate;
    private final int[] slots;
    private final boolean allowInsert;
    private final boolean allowExtract;

    MachineSideHandler(IItemHandler delegate, int firstSlot, int count,
                       boolean allowInsert, boolean allowExtract) {
        this.delegate = delegate;
        this.slots = new int[count];
        for (int i = 0; i < count; i++) {
            this.slots[i] = firstSlot + i;
        }
        this.allowInsert = allowInsert;
        this.allowExtract = allowExtract;
    }

    /** 什么都不暴露的面。 */
    static IItemHandler empty() {
        return new IItemHandler() {
            @Override
            public int getSlots() {
                return 0;
            }

            @Override
            public ItemStack getStackInSlot(int slot) {
                return ItemStack.EMPTY;
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                return stack;
            }

            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                return ItemStack.EMPTY;
            }

            @Override
            public int getSlotLimit(int slot) {
                return 0;
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return false;
            }
        };
    }

    @Override
    public int getSlots() {
        return slots.length;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return delegate.getStackInSlot(slots[slot]);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (!allowInsert) {
            return stack;
        }
        return delegate.insertItem(slots[slot], stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (!allowExtract) {
            return ItemStack.EMPTY;
        }
        return delegate.extractItem(slots[slot], amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        return delegate.getSlotLimit(slots[slot]);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return allowInsert && delegate.isItemValid(slots[slot], stack);
    }
}
