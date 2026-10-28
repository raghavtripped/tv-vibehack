package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StockHomeRedirectTest {

    @Test
    public void lateStockHomeRightAfterOpeningAnAppIsLeftAlone() {
        // Xiaomi MOOR2: Hotstar opened at 55.6 s, the stock home's window showed at 57.0 s.
        assertTrue(HomeKeyService.justOpenedApp(57_000, 55_600));
        assertTrue(HomeKeyService.justOpenedApp(60_599, 55_600));
    }

    @Test
    public void stockHomeLaterOrWithoutAnAppOpenIsSentBack() {
        assertFalse(HomeKeyService.justOpenedApp(60_600, 55_600));
        assertFalse(HomeKeyService.justOpenedApp(3_000, 0));
    }
}
