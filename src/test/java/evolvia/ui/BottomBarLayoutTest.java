package evolvia.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 9i: the sections of the bottom bar never overlap on the supported window sizes. */
class BottomBarLayoutTest {

    @Test
    void sectionsDoNotOverlap() {
        for (float width : new float[]{1024f, 1280f, 1600f, 1920f, 2560f}) {
            for (int cards : new int[]{4, 6, 8}) {
                BottomBar.Layout l = BottomBar.layout(width, cards);
                String at = width + " px, " + cards + " cards";
                assertTrue(l.peopleX() >= 0f, at);
                assertTrue(l.stockX() >= l.peopleX() + BottomBar.PEOPLE_WIDTH, at);
                assertTrue(l.tabsX() >= l.stockX() + BottomBar.STOCK_WIDTH, at);
                assertTrue(l.cardsX() >= l.tabsX() + BottomBar.TABS_WIDTH, at);
                assertTrue(l.cardWidth() >= BottomBar.MIN_CARD && l.cardWidth() <= BottomBar.MAX_CARD, at);
                if (width >= 1024f && cards <= 6) {
                    assertTrue(l.cardsEnd(cards) <= l.faithX(), at + ": cards end " + l.cardsEnd(cards) + " > faith " + l.faithX());
                }
                assertTrue(l.faithX() + BottomBar.FAITH_WIDTH <= width, at);
            }
        }
    }

    @Test
    void cardsUseTheRoomAndStayCentred() {
        BottomBar.Layout wide = BottomBar.layout(1920f, 6);
        assertEquals(BottomBar.MAX_CARD, wide.cardWidth(), 1e-3, "wide screens get full cards");
        float left = wide.cardsX() - (wide.tabsX() + BottomBar.TABS_WIDTH + BottomBar.GAP);
        float right = wide.faithX() - BottomBar.GAP - wide.cardsEnd(6);
        assertEquals(left, right, 1e-3, "centred between the tabs and faith");
        assertTrue(BottomBar.layout(1024f, 6).cardWidth() < BottomBar.layout(1280f, 6).cardWidth(), "narrow screens shrink the cards");
    }
}
