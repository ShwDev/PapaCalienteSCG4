package com.shaw.papacaliente;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Geometría del tablero. La casilla (i, j) del área máxima está i bloques hacia delante y j hacia la
 * derecha de la esquina origen. El tablero de cada ronda está centrado dentro del área máxima.
 */
public final class Board {
    public static final int MAX_SIZE = 10;
    public static final int MIN_SIZE = 3;
    public static final int MAX_ROUND = MAX_SIZE - MIN_SIZE + 1; // 8 rondas: 3x3 … 10x10

    private Board() {
    }

    /** Casilla (i, j) del área máxima. */
    public static BlockPos cell(BlockPos origin, Direction forward, int i, int j) {
        Direction right = forward.getClockWise();
        return origin.offset(
                forward.getStepX() * i + right.getStepX() * j,
                0,
                forward.getStepZ() * i + right.getStepZ() * j);
    }

    /** Desplazamiento para centrar un tablero de este tamaño dentro del área máxima. */
    public static int offset(int size) {
        return (MAX_SIZE - size) / 2;
    }

    /** Casilla (a, b) de un tablero centrado de tamaño {@code size}. */
    public static BlockPos boardCell(BlockPos origin, Direction forward, int size, int a, int b) {
        int off = offset(size);
        return cell(origin, forward, off + a, off + b);
    }

    /** Bloque de soporte del botón: a la derecha del área, a mitad del borde y justo por fuera. */
    public static BlockPos buttonSupport(BlockPos origin, Direction forward) {
        return cell(origin, forward, MAX_SIZE / 2, MAX_SIZE);
    }

    public static BlockPos buttonPos(BlockPos origin, Direction forward) {
        return buttonSupport(origin, forward).above();
    }

    public static int sizeForRound(int round) {
        return Math.min(MAX_SIZE, MIN_SIZE + round - 1);
    }

    /** Papas Bomba por jugador: 3x3=1, 4x4=2 … 10x10=8. */
    public static int bombsPerPlayer(int round) {
        return Math.min(8, round);
    }
}
