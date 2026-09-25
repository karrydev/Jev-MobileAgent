package com.jev.mobileagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class VlmCoordinateProtocolTest {
    @Test
    public void guiPlusResizesNonSquareImagesToTwentyEightMultiplesBelowProviderLimit() {
        VlmCoordinateProtocol protocol = VlmCoordinateProtocol.forModel("gui-plus");

        VlmCoordinateProtocol.ImageSize size = protocol.uploadSize(1080, 2400);

        assertEquals(672, size.width);
        assertEquals(1484, size.height);
        assertEquals(0, size.width % VlmCoordinateProtocol.GUI_PLUS_IMAGE_MULTIPLE);
        assertEquals(0, size.height % VlmCoordinateProtocol.GUI_PLUS_IMAGE_MULTIPLE);
        assertTrue((long) size.width * size.height <= VlmCoordinateProtocol.GUI_PLUS_MAX_PIXELS);
        assertTrue((long) size.width * size.height >= VlmCoordinateProtocol.GUI_PLUS_MIN_PIXELS);
    }

    @Test
    public void guiPlusCoordinatesMapFromResizedImageIntoNonSquareScreen() {
        VlmCoordinateProtocol protocol = VlmCoordinateProtocol.forModel("gui-plus");
        VlmCoordinateProtocol.ImageSize image = protocol.uploadSize(1080, 2400);

        assertEquals(540, protocol.toScreenPixel(336, image.width, 1080));
        assertEquals(1200, protocol.toScreenPixel(742, image.height, 2400));
        assertEquals(1078, protocol.toScreenPixel(image.width - 1, image.width, 1080));
        assertEquals(2398, protocol.toScreenPixel(image.height - 1, image.height, 2400));
    }

    @Test
    public void onlyExactGuiPlusUsesPixelCoordinatesAndTaskRestoreUsesItsSavedModel() {
        assertTrue(VlmCoordinateProtocol.forModel("gui-plus").usesPixelCoordinates());
        assertFalse(VlmCoordinateProtocol.forModel("gui-plus-2026-02-26").usesPixelCoordinates());
        assertFalse(VlmCoordinateProtocol.forTaskModel("gui-plus-2026-02-26", "gui-plus-2026-02-26")
                .usesPixelCoordinates());
        assertTrue(VlmCoordinateProtocol.forTaskModel("gui-plus", "gui-plus").usesPixelCoordinates());
    }

    @Test
    public void taskRestoreRejectsAChangedConfiguredModel() {
        try {
            VlmCoordinateProtocol.forTaskModel("gui-plus", "gui-plus-2026-02-26");
            fail("a resumed task cannot silently switch model coordinate protocols");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("saved task model"));
        }
    }

    @Test
    public void guiPlusRejectsNegativeNonFiniteAndOutsideImageCoordinates() {
        VlmCoordinateProtocol protocol = VlmCoordinateProtocol.forModel("gui-plus");
        assertBadCoordinate(protocol, -0.1, 672, 1080);
        assertBadCoordinate(protocol, 672, 672, 1080);
        assertBadCoordinate(protocol, Double.NaN, 672, 1080);
        assertBadCoordinate(protocol, Double.POSITIVE_INFINITY, 672, 1080);
    }

    private static void assertBadCoordinate(VlmCoordinateProtocol protocol,
            double coordinate, int imageSize, int screenSize) {
        try {
            protocol.toScreenPixel(coordinate, imageSize, screenSize);
            fail("invalid GUI-Plus image coordinate was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("outside"));
        }
    }
}
