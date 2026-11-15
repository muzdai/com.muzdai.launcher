package com.muzdai.launcher;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GridNavigationTest {
    @Test public void rowEndsStopFocus() {
        assertTrue(GridNavigation.atHorizontalEdge(0, 11, 4, true));
        assertTrue(GridNavigation.atHorizontalEdge(3, 11, 4, false));
        assertTrue(GridNavigation.atHorizontalEdge(10, 11, 4, false));
        assertFalse(GridNavigation.atHorizontalEdge(1, 11, 4, true));
        assertFalse(GridNavigation.atHorizontalEdge(1, 11, 4, false));
    }

    @Test public void singleColumnHasBothHorizontalEdges() {
        assertTrue(GridNavigation.atHorizontalEdge(1, 3, 1, true));
        assertTrue(GridNavigation.atHorizontalEdge(1, 3, 1, false));
    }

    @Test public void horizontalEdgesMatchExplicitRowsAcrossColumnCounts() {
        for (GridCase grid : grids()) {
            for (int position = 0; position < grid.count; position++) {
                for (boolean left : new boolean[] {false, true}) {
                    boolean expected = isEdge(grid.rows, position, left);
                    String message = "count=" + grid.count + ", columns=" + grid.columns
                            + ", position=" + position + ", direction=" + (left ? "left" : "right");
                    assertEquals(message, expected, GridNavigation.atHorizontalEdge(
                            position, grid.count, grid.columns, left));
                }
            }
        }
    }

    @Test public void sparseDockRedirectsEmptySlotsAndKeepsGridRowBoundary() {
        int dockApps = 2;
        int columns = 6;
        int gridApps = 12;
        int emptySlots = DockSlots.empty(dockApps, columns, dockApps + gridApps, false);
        int totalSlots = dockApps + gridApps + emptySlots;
        assertEquals("two occupied dock slots leave four empty slots", 4, emptySlots);
        for (int position = dockApps; position < dockApps + emptySlots; position++) {
            assertEquals("empty dock position=" + position, dockApps - 1,
                    DockSlots.focusable(position, dockApps, emptySlots));
        }
        assertEquals(0, DockSlots.focusable(0, dockApps, emptySlots));
        assertEquals(dockApps + emptySlots, DockSlots.focusable(dockApps + emptySlots, dockApps, emptySlots));

        assertTrue(GridNavigation.atHorizontalEdge(0, dockApps, columns, true));
        assertFalse(GridNavigation.atHorizontalEdge(0, dockApps, columns, false));
        assertTrue(GridNavigation.atHorizontalEdge(1, dockApps, columns, false));
        assertFalse(GridNavigation.atHorizontalEdge(1, dockApps, columns, true));
        // MainActivity must pass the reduced dock count to keep the outward dock edge here.
        assertTrue(GridNavigation.atHorizontalEdge(columns, totalSlots, columns, true));
        assertFalse(GridNavigation.atHorizontalEdge(columns, totalSlots, columns, false));
    }

    private static List<GridCase> grids() {
        List<GridCase> grids = new ArrayList<>();
        for (int columns = 1; columns <= 8; columns++) {
            grids.add(new GridCase(columns, 1));
            grids.add(new GridCase(columns, columns));
            grids.add(new GridCase(columns, columns, 1));
            grids.add(new GridCase(columns, columns, columns));
        }
        return grids;
    }

    /** Row lengths declare the expected groups; positions are assigned in reading order. */
    private static final class GridCase {
        final int columns;
        final int count;
        final List<List<Integer>> rows = new ArrayList<>();

        GridCase(int columns, int... rowLengths) {
            this.columns = columns;
            int nextPosition = 0;
            for (int rowLength : rowLengths) {
                List<Integer> row = new ArrayList<>();
                for (int i = 0; i < rowLength; i++) row.add(nextPosition++);
                rows.add(row);
            }
            count = nextPosition;
        }
    }

    private static boolean isEdge(List<List<Integer>> rows, int position, boolean left) {
        for (List<Integer> row : rows) {
            if (position == (left ? row.get(0) : row.get(row.size() - 1))) return true;
        }
        return false;
    }
}
