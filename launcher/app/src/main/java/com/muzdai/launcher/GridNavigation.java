package com.muzdai.launcher;

/** Left/right boundaries of a grid row, so focus never wraps to the next row. */
final class GridNavigation {
    private GridNavigation() { }

    static boolean atHorizontalEdge(int position, int count, int columns, boolean left) {
        if (left) return position % columns == 0;
        return position % columns == columns - 1 || position == count - 1;
    }
}
