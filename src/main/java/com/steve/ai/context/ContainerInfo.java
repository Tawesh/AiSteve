package com.steve.ai.context;

import net.minecraft.core.BlockPos;

/**
 * Information about a nearby container (chest, barrel, furnace, etc).
 */
public class ContainerInfo {

    private final BlockPos position;
    private final String type;

    public ContainerInfo(BlockPos position, String type) {
        this.position = position;
        this.type = type;
    }

    public BlockPos getPosition() {
        return position;
    }

    public String getType() {
        return type;
    }
}
