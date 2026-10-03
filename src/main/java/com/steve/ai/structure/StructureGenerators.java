package com.steve.ai.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Improved structure generation with better aesthetics and material coordination.
 * Follows Minecraft building conventions for authentic, visually appealing structures.
 */
public class StructureGenerators {

    public static List<BlockPlacement> generate(String structureType, BlockPos start, int width, int height, int depth, List<Block> materials) {
        return switch (structureType.toLowerCase()) {
            case "house", "home" -> buildBeautifulHouse(start, Math.max(7, width), Math.max(5, height), Math.max(7, depth));
            case "castle", "catle", "fort" -> buildCastle(start, width, height, depth);
            case "tower" -> buildTower(start, Math.max(5, width), Math.max(12, height));
            case "wall" -> buildWall(start, width, height, materials);
            case "platform" -> buildPlatform(start, width, depth, materials);
            case "barn", "shed" -> buildBarn(start, Math.max(9, width), Math.max(6, height), Math.max(11, depth));
            case "modern", "modern_house" -> buildModernHouse(start, Math.max(9, width), Math.max(5, height), Math.max(9, depth));
            case "cottage" -> buildCottage(start, Math.max(6, width), Math.max(5, height), Math.max(6, depth));
            case "box", "cube" -> buildBox(start, width, height, depth, materials);
            default -> buildBeautifulHouse(start, 7, 5, 7);
        };
    }

    /**
     * Beautiful medieval-style house with proper material palette and design.
     * Uses oak planks, cobblestone foundation, and spruce roof for classic look.
     */
    private static List<BlockPlacement> buildBeautifulHouse(BlockPos start, int width, int height, int depth) {
        List<BlockPlacement> blocks = new ArrayList<>();

        // Material palette - coordinated and authentic
        Block foundation = Blocks.COBBLESTONE;
        Block mainWall = Blocks.OAK_PLANKS;
        Block frameAccent = Blocks.STRIPPED_OAK_LOG;
        Block roofMaterial = Blocks.SPRUCE_STAIRS;
        Block roofTrim = Blocks.SPRUCE_PLANKS;
        Block window = Blocks.GLASS_PANE;
        Block door = Blocks.OAK_DOOR;

        // Foundation layer (cobblestone base)
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 0, z), foundation));
            }
        }

        // Floor (oak planks)
        for (int x = 1; x < width - 1; x++) {
            for (int z = 1; z < depth - 1; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 1, z), Blocks.OAK_PLANKS));
            }
        }

        // Walls with timber frame design (layers 1-4)
        for (int y = 1; y <= height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean isPerimeter = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
                    if (!isPerimeter) continue;

                    BlockPos pos = start.offset(x, y, z);
                    boolean isCorner = (x == 0 || x == width - 1) && (z == 0 || z == depth - 1);

                    // Door placement (front center, 2 blocks tall)
                    if (z == 0 && x == width / 2 && y >= 1 && y <= 2) {
                        if (y == 1) {
                            blocks.add(new BlockPlacement(pos, door));
                        }
                        continue;
                    }

                    // Corner posts (timber frame)
                    if (isCorner) {
                        blocks.add(new BlockPlacement(pos, frameAccent));
                        continue;
                    }

                    // Windows (2 blocks tall, positioned symmetrically)
                    boolean isWindowHeight = (y == 2 || y == 3);
                    boolean frontWindow = (z == 0 && (x == 2 || x == width - 3));
                    boolean backWindow = (z == depth - 1 && (x == 2 || x == width - 3));
                    boolean sideWindow = ((x == 0 || x == width - 1) && z == depth / 2);

                    if (isWindowHeight && (frontWindow || backWindow || sideWindow)) {
                        blocks.add(new BlockPlacement(pos, window));
                    } else {
                        // Main wall with occasional timber frame accents
                        if (y == 1 || y == height || (x % 3 == 0 && z % 3 == 0)) {
                            blocks.add(new BlockPlacement(pos, frameAccent));
                        } else {
                            blocks.add(new BlockPlacement(pos, mainWall));
                        }
                    }
                }
            }
        }

        // Peaked roof with proper stair orientation
        int roofHeight = height + 1;
        int peakHeight = roofHeight + depth / 2;

        for (int z = 0; z < depth; z++) {
            int currentRoofY = roofHeight + Math.min(z, depth - 1 - z);

            for (int x = 0; x < width; x++) {
                // Sloped roof using stairs
                blocks.add(new BlockPlacement(start.offset(x, currentRoofY, z), roofTrim));

                // Ridge cap at peak
                if (z == depth / 2 || z == (depth / 2) - 1) {
                    blocks.add(new BlockPlacement(start.offset(x, currentRoofY + 1, z), roofTrim));
                }
            }
        }

        // Chimney (decorative detail)
        int chimneyX = width - 2;
        int chimneyZ = depth - 2;
        for (int y = 1; y <= peakHeight + 2; y++) {
            blocks.add(new BlockPlacement(start.offset(chimneyX, y, chimneyZ), Blocks.BRICKS));
        }

        return blocks;
    }

    /**
     * Cozy cottage with stone and wood design.
     */
    private static List<BlockPlacement> buildCottage(BlockPos start, int width, int height, int depth) {
        List<BlockPlacement> blocks = new ArrayList<>();

        Block stone = Blocks.STONE_BRICKS;
        Block wood = Blocks.DARK_OAK_PLANKS;
        Block roof = Blocks.DARK_OAK_STAIRS;
        Block window = Blocks.GLASS_PANE;

        // Stone foundation and lower walls
        for (int y = 0; y <= 2; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean isPerimeter = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
                    if (y == 0 || isPerimeter) {
                        blocks.add(new BlockPlacement(start.offset(x, y, z), stone));
                    }
                }
            }
        }

        // Upper wooden walls
        for (int y = 3; y <= height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean isPerimeter = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
                    if (isPerimeter) {
                        // Windows
                        if (y == 3 && ((x == 2 && z == 0) || (x == width - 3 && z == 0))) {
                            blocks.add(new BlockPlacement(start.offset(x, y, z), window));
                        } else {
                            blocks.add(new BlockPlacement(start.offset(x, y, z), wood));
                        }
                    }
                }
            }
        }

        // Thatched-style roof
        for (int z = 0; z < depth; z++) {
            int roofY = height + 1 + Math.min(z, depth - 1 - z);
            for (int x = 0; x < width; x++) {
                blocks.add(new BlockPlacement(start.offset(x, roofY, z), Blocks.HAY_BLOCK));
            }
        }

        return blocks;
    }

    /**
     * Impressive castle with proper medieval architecture.
     */
    private static List<BlockPlacement> buildCastle(BlockPos start, int width, int height, int depth) {
        List<BlockPlacement> blocks = new ArrayList<>();

        Block mainStone = Blocks.STONE_BRICKS;
        Block darkStone = Blocks.CHISELED_STONE_BRICKS;
        Block wall = Blocks.COBBLESTONE;

        // Main courtyard floor
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 0, z), mainStone));
            }
        }

        // Outer walls with battlements
        for (int y = 1; y <= height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean isPerimeter = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
                    boolean isCorner = (x < 3 || x >= width - 3) && (z < 3 || z >= depth - 3);

                    if (isPerimeter && !isCorner) {
                        blocks.add(new BlockPlacement(start.offset(x, y, z), wall));
                    }
                }
            }
        }

        // Battlements (crenellations)
        for (int x = 0; x < width; x += 2) {
            blocks.add(new BlockPlacement(start.offset(x, height + 1, 0), wall));
            blocks.add(new BlockPlacement(start.offset(x, height + 1, depth - 1), wall));
        }
        for (int z = 0; z < depth; z += 2) {
            blocks.add(new BlockPlacement(start.offset(0, height + 1, z), wall));
            blocks.add(new BlockPlacement(start.offset(width - 1, height + 1, z), wall));
        }

        // Corner towers (4 impressive towers)
        int towerHeight = height + 8;
        int[][] corners = {{0, 0}, {width - 4, 0}, {0, depth - 4}, {width - 4, depth - 4}};

        for (int[] corner : corners) {
            // Tower body
            for (int y = 0; y <= towerHeight; y++) {
                for (int dx = 0; dx < 4; dx++) {
                    for (int dz = 0; dz < 4; dz++) {
                        boolean isTowerWall = (dx == 0 || dx == 3 || dz == 0 || dz == 3);
                        if (isTowerWall || y == 0) {
                            Block material = (y % 3 == 0) ? darkStone : mainStone;
                            blocks.add(new BlockPlacement(start.offset(corner[0] + dx, y, corner[1] + dz), material));
                        }
                    }
                }
            }

            // Tower roof (conical)
            for (int i = 0; i < 3; i++) {
                for (int dx = i; dx < 4 - i; dx++) {
                    for (int dz = i; dz < 4 - i; dz++) {
                        blocks.add(new BlockPlacement(
                            start.offset(corner[0] + dx, towerHeight + 1 + i, corner[1] + dz),
                            Blocks.STONE_BRICK_STAIRS
                        ));
                    }
                }
            }
        }

        return blocks;
    }

    /**
     * Tall watchtower with observation deck.
     */
    private static List<BlockPlacement> buildTower(BlockPos start, int width, int height) {
        List<BlockPlacement> blocks = new ArrayList<>();

        Block stone = Blocks.STONE_BRICKS;
        Block accent = Blocks.CHISELED_STONE_BRICKS;
        Block window = Blocks.IRON_BARS;

        // Tower shaft
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    boolean isPerimeter = (x == 0 || x == width - 1 || z == 0 || z == width - 1);
                    boolean isCorner = (x == 0 || x == width - 1) && (z == 0 || z == width - 1);

                    if (y == 0 || isPerimeter) {
                        Block material = isCorner ? accent : stone;

                        // Arrow slits every few levels
                        if (y % 4 == 2 && !isCorner && (x == width / 2 || z == width / 2)) {
                            blocks.add(new BlockPlacement(start.offset(x, y, z), window));
                        } else {
                            blocks.add(new BlockPlacement(start.offset(x, y, z), material));
                        }
                    }
                }
            }
        }

        // Observation platform at top
        for (int x = -1; x <= width; x++) {
            for (int z = -1; z <= width; z++) {
                blocks.add(new BlockPlacement(start.offset(x, height, z), Blocks.OAK_PLANKS));
            }
        }

        // Platform railings
        for (int x = -1; x <= width; x++) {
            blocks.add(new BlockPlacement(start.offset(x, height + 1, -1), Blocks.OAK_FENCE));
            blocks.add(new BlockPlacement(start.offset(x, height + 1, width), Blocks.OAK_FENCE));
        }
        for (int z = 0; z < width; z++) {
            blocks.add(new BlockPlacement(start.offset(-1, height + 1, z), Blocks.OAK_FENCE));
            blocks.add(new BlockPlacement(start.offset(width, height + 1, z), Blocks.OAK_FENCE));
        }

        return blocks;
    }

    /**
     * Modern house with clean lines and glass facade.
     */
    private static List<BlockPlacement> buildModernHouse(BlockPos start, int width, int height, int depth) {
        List<BlockPlacement> blocks = new ArrayList<>();

        Block concrete = Blocks.WHITE_CONCRETE;
        Block darkConcrete = Blocks.GRAY_CONCRETE;
        Block glass = Blocks.GLASS;
        Block floor = Blocks.POLISHED_ANDESITE;

        // Concrete platform/foundation
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 0, z), darkConcrete));
                blocks.add(new BlockPlacement(start.offset(x, 1, z), floor));
            }
        }

        // Modern walls - lots of glass on front
        for (int y = 2; y <= height; y++) {
            for (int x = 0; x < width; x++) {
                // Front - full glass facade
                if (x % 2 == 1 || y >= 3) {
                    blocks.add(new BlockPlacement(start.offset(x, y, 0), glass));
                } else {
                    blocks.add(new BlockPlacement(start.offset(x, y, 0), concrete));
                }

                // Back - solid wall
                blocks.add(new BlockPlacement(start.offset(x, y, depth - 1), concrete));
            }

            // Sides - mixed glass and concrete
            for (int z = 1; z < depth - 1; z++) {
                boolean isGlassSection = (z >= depth / 3 && z <= 2 * depth / 3 && y >= 2 && y <= height - 1);
                blocks.add(new BlockPlacement(start.offset(0, y, z), isGlassSection ? glass : concrete));
                blocks.add(new BlockPlacement(start.offset(width - 1, y, z), isGlassSection ? glass : concrete));
            }
        }

        // Flat roof with overhang
        for (int x = -1; x <= width; x++) {
            for (int z = -1; z <= depth; z++) {
                blocks.add(new BlockPlacement(start.offset(x, height + 1, z), darkConcrete));
            }
        }

        return blocks;
    }

    /**
     * Large barn with hayloft and proper timber frame.
     */
    private static List<BlockPlacement> buildBarn(BlockPos start, int width, int height, int depth) {
        List<BlockPlacement> blocks = new ArrayList<>();

        Block planks = Blocks.SPRUCE_PLANKS;
        Block log = Blocks.SPRUCE_LOG;
        Block roof = Blocks.RED_TERRACOTTA;

        // Stone foundation
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 0, z), Blocks.COBBLESTONE));
            }
        }

        // Timber frame structure
        for (int y = 1; y <= height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean isPerimeter = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
                    if (!isPerimeter) continue;

                    boolean isPost = (x % 3 == 0 && (z == 0 || z == depth - 1)) ||
                                   (z % 3 == 0 && (x == 0 || x == width - 1));

                    // Large barn door opening (front center)
                    if (z == 0 && x >= width / 2 - 1 && x <= width / 2 + 1 && y <= 3) {
                        continue;
                    }

                    blocks.add(new BlockPlacement(start.offset(x, y, z), isPost ? log : planks));
                }
            }
        }

        // Gambrel roof (classic barn roof)
        for (int z = 0; z < depth; z++) {
            int distFromCenter = Math.abs(z - depth / 2);
            int roofY = height + 1 + (depth / 2 - distFromCenter) / 2;

            for (int x = 0; x < width; x++) {
                blocks.add(new BlockPlacement(start.offset(x, roofY, z), roof));
                if (distFromCenter < depth / 4) {
                    blocks.add(new BlockPlacement(start.offset(x, roofY + 1, z), roof));
                }
            }
        }

        return blocks;
    }

    // Simple utility structures
    private static List<BlockPlacement> buildWall(BlockPos start, int width, int height, List<Block> materials) {
        List<BlockPlacement> blocks = new ArrayList<>();
        Block material = materials.isEmpty() ? Blocks.COBBLESTONE : materials.get(0);

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                blocks.add(new BlockPlacement(start.offset(x, y, 0), material));
            }
        }
        return blocks;
    }

    private static List<BlockPlacement> buildPlatform(BlockPos start, int width, int depth, List<Block> materials) {
        List<BlockPlacement> blocks = new ArrayList<>();
        Block material = materials.isEmpty() ? Blocks.OAK_PLANKS : materials.get(0);

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 0, z), material));
            }
        }
        return blocks;
    }

    private static List<BlockPlacement> buildBox(BlockPos start, int width, int height, int depth, List<Block> materials) {
        List<BlockPlacement> blocks = new ArrayList<>();
        Block material = materials.isEmpty() ? Blocks.STONE : materials.get(0);

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    blocks.add(new BlockPlacement(start.offset(x, y, z), material));
                }
            }
        }
        return blocks;
    }
}
