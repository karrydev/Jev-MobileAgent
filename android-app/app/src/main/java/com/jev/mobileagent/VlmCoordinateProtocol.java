package com.jev.mobileagent;

/** Coordinate and image-size contract for the exact GUI-Plus model identifier. */
public final class VlmCoordinateProtocol {
    public static final String GUI_PLUS_MODEL = "gui-plus";
    public static final String GUI_PLUS_IMAGE_TRANSFORM = "gui-plus-28px-maxpixels-v1";
    public static final int GUI_PLUS_IMAGE_MULTIPLE = 28;
    public static final int GUI_PLUS_MIN_PIXELS = 3_136;
    public static final int GUI_PLUS_MAX_PIXELS = 1_003_520;

    private static final VlmCoordinateProtocol NORMALIZED =
            new VlmCoordinateProtocol(false, "normalized_0_1000");
    private static final VlmCoordinateProtocol GUI_PLUS =
            new VlmCoordinateProtocol(true, GUI_PLUS_IMAGE_TRANSFORM);

    private final boolean pixelCoordinates;
    private final String imageTransform;

    private VlmCoordinateProtocol(boolean pixelCoordinates, String imageTransform) {
        this.pixelCoordinates = pixelCoordinates;
        this.imageTransform = imageTransform;
    }

    /** Only the undated model uses pixel coordinates; dated model ids keep the normalized contract. */
    public static VlmCoordinateProtocol forModel(String model) {
        return GUI_PLUS_MODEL.equals(model == null ? "" : model.trim()) ? GUI_PLUS : NORMALIZED;
    }

    /** Bind a resumed task's protocol to the model recorded when the task was created. */
    public static VlmCoordinateProtocol forTaskModel(String savedModel, String configuredModel) {
        String saved = savedModel == null ? "" : savedModel.trim();
        String configured = configuredModel == null ? "" : configuredModel.trim();
        if (!saved.isEmpty() && !saved.equals(configured)) {
            throw new IllegalArgumentException("configured model does not match the saved task model");
        }
        return forModel(saved.isEmpty() ? configured : saved);
    }

    public boolean usesPixelCoordinates() {
        return pixelCoordinates;
    }

    public String imageTransform() {
        return imageTransform;
    }

    /** Deterministic GUI-Plus upload size: 28-pixel multiples within the documented pixel bounds. */
    public ImageSize uploadSize(int sourceWidth, int sourceHeight) {
        if (sourceWidth < 1 || sourceHeight < 1) {
            throw new IllegalArgumentException("source image dimensions must be positive");
        }
        if (!pixelCoordinates) {
            return new ImageSize(sourceWidth, sourceHeight);
        }

        double sourcePixels = (double) sourceWidth * (double) sourceHeight;
        double scale = Math.min(1.0, Math.sqrt(GUI_PLUS_MAX_PIXELS / sourcePixels));
        if (sourcePixels < GUI_PLUS_MIN_PIXELS) {
            scale = Math.sqrt(GUI_PLUS_MIN_PIXELS / sourcePixels);
        }
        int width = nearestMultiple(sourceWidth * scale);
        int height = nearestMultiple(sourceHeight * scale);
        while ((long) width * height > GUI_PLUS_MAX_PIXELS) {
            if (width / (double) sourceWidth >= height / (double) sourceHeight) {
                width -= GUI_PLUS_IMAGE_MULTIPLE;
            } else {
                height -= GUI_PLUS_IMAGE_MULTIPLE;
            }
            if (width < GUI_PLUS_IMAGE_MULTIPLE || height < GUI_PLUS_IMAGE_MULTIPLE) {
                throw new IllegalArgumentException("image aspect ratio cannot fit GUI-Plus pixel limits");
            }
        }
        while ((long) width * height < GUI_PLUS_MIN_PIXELS) {
            if (width / (double) sourceWidth <= height / (double) sourceHeight) {
                width += GUI_PLUS_IMAGE_MULTIPLE;
            } else {
                height += GUI_PLUS_IMAGE_MULTIPLE;
            }
        }
        return new ImageSize(width, height);
    }

    /** Map a GUI-Plus image pixel into the observation's screen pixel space; never clamps bad input. */
    public int toScreenPixel(double imageCoordinate, int imageSize, int screenSize) {
        if (!pixelCoordinates || !Double.isFinite(imageCoordinate)
                || imageSize < 1 || screenSize < 1
                || imageCoordinate < 0.0 || imageCoordinate >= imageSize) {
            throw new IllegalArgumentException("GUI-Plus pixel coordinate is outside its image");
        }
        return Math.min(screenSize - 1, (int) (imageCoordinate * screenSize / imageSize));
    }

    private static int nearestMultiple(double value) {
        long rounded = Math.round(value / GUI_PLUS_IMAGE_MULTIPLE) * GUI_PLUS_IMAGE_MULTIPLE;
        return (int) Math.max(GUI_PLUS_IMAGE_MULTIPLE, Math.min(Integer.MAX_VALUE, rounded));
    }

    public static final class ImageSize {
        public final int width;
        public final int height;

        ImageSize(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public boolean equalsSize(int expectedWidth, int expectedHeight) {
            return width == expectedWidth && height == expectedHeight;
        }
    }
}
