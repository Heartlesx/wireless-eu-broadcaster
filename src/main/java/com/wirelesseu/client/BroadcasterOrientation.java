package com.wirelesseu.client;

import codechicken.lib.vec.Vertex5;
import net.minecraft.util.EnumFacing;

/** Maps a north-facing texture cube into the broadcaster's six possible orientations. */
final class BroadcasterOrientation {
    private BroadcasterOrientation() {
    }

    static EnumFacing up(EnumFacing front) {
        return front == EnumFacing.UP ? EnumFacing.SOUTH
                : front == EnumFacing.DOWN ? EnumFacing.NORTH : EnumFacing.UP;
    }

    static EnumFacing right(EnumFacing front) {
        return front.getAxis() == EnumFacing.Axis.Y ? EnumFacing.EAST : front.rotateY();
    }

    static EnumFacing localSide(EnumFacing side, EnumFacing front) {
        if (side == front) return EnumFacing.NORTH;
        if (side == front.getOpposite()) return EnumFacing.SOUTH;
        if (side == up(front)) return EnumFacing.UP;
        if (side == up(front).getOpposite()) return EnumFacing.DOWN;
        return side == right(front) ? EnumFacing.EAST : EnumFacing.WEST;
    }

    static void setUv(Vertex5 vertex, EnumFacing side, EnumFacing front) {
        EnumFacing right = right(front);
        EnumFacing up = up(front);
        double wx = vertex.vec.x - 0.5;
        double wy = vertex.vec.y - 0.5;
        double wz = vertex.vec.z - 0.5;
        double x = 0.5 + wx * right.getXOffset() + wy * right.getYOffset() + wz * right.getZOffset();
        double y = 0.5 + wx * up.getXOffset() + wy * up.getYOffset() + wz * up.getZOffset();
        double z = 0.5 - wx * front.getXOffset() - wy * front.getYOffset() - wz * front.getZOffset();
        switch (localSide(side, front)) {
            case DOWN: vertex.uv.set(x, 1 - z); break;
            case UP: vertex.uv.set(x, z); break;
            case NORTH: vertex.uv.set(1 - x, 1 - y); break;
            case SOUTH: vertex.uv.set(x, 1 - y); break;
            case WEST: vertex.uv.set(z, 1 - y); break;
            case EAST: vertex.uv.set(1 - z, 1 - y); break;
        }
    }
}
