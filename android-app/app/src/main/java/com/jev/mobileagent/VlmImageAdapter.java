package com.jev.mobileagent;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/** Prepares in-memory VLM images and safe dimensions for the local request trace. */
public final class VlmImageAdapter {
    private VlmImageAdapter() {
    }

    public static PreparedImages prepare(VlmCoordinateProtocol protocol, JSONArray screenshots)
            throws JSONException {
        JSONArray preparedImages = new JSONArray();
        JSONArray requestMetadata = new JSONArray();
        for (int i = 0; screenshots != null && i < screenshots.length(); i++) {
            JSONObject screenshot = screenshots.optJSONObject(i);
            if (screenshot == null || screenshot.optString("png_base64", "").isEmpty()) {
                throw new JSONException("required screenshot is unavailable");
            }
            JSONObject prepared = new JSONObject(screenshot.toString());
            JSONObject metadata;
            if (protocol.usesPixelCoordinates()) {
                GuiPlusImage result = prepareGuiPlusImage(protocol, screenshot, prepared);
                metadata = result.metadata;
            } else {
                int width = positiveOrZero(screenshot.optInt("width_px", 0));
                int height = positiveOrZero(screenshot.optInt("height_px", 0));
                prepared.put("width_px", width);
                prepared.put("height_px", height);
                metadata = transferMetadata(screenshot, width, height, width, height, "identity");
            }
            preparedImages.put(prepared);
            requestMetadata.put(metadata);
        }
        return new PreparedImages(preparedImages, requestMetadata);
    }

    private static GuiPlusImage prepareGuiPlusImage(
            VlmCoordinateProtocol protocol, JSONObject screenshot, JSONObject prepared)
            throws JSONException {
        String encoded = screenshot.optString("png_base64", "");
        final byte[] sourceBytes;
        try {
            sourceBytes = Base64.decode(encoded, Base64.DEFAULT);
        } catch (IllegalArgumentException exception) {
            throw new JSONException("screenshot image encoding is invalid");
        }
        Bitmap source = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.length);
        if (source == null) {
            throw new JSONException("screenshot image cannot be decoded");
        }
        Bitmap upload = source;
        try {
            int sourceWidth = source.getWidth();
            int sourceHeight = source.getHeight();
            if (screenshot.optInt("width_px", 0) != sourceWidth
                    || screenshot.optInt("height_px", 0) != sourceHeight) {
                throw new JSONException("screenshot dimensions do not match its image");
            }
            VlmCoordinateProtocol.ImageSize uploadSize = protocol.uploadSize(sourceWidth, sourceHeight);
            if (!uploadSize.equalsSize(sourceWidth, sourceHeight)) {
                upload = Bitmap.createScaledBitmap(source, uploadSize.width, uploadSize.height, true);
                if (upload == null) {
                    throw new JSONException("screenshot resize failed");
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!upload.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new JSONException("resized screenshot encoding failed");
            }
            prepared.put("png_base64", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
            prepared.put("width_px", upload.getWidth());
            prepared.put("height_px", upload.getHeight());
            String transform = upload == source
                    ? protocol.imageTransform() + ":identity"
                    : protocol.imageTransform() + ":resize";
            JSONObject metadata = transferMetadata(screenshot, sourceWidth, sourceHeight,
                    upload.getWidth(), upload.getHeight(), transform);
            long uploadPixels = (long) upload.getWidth() * upload.getHeight();
            if (uploadPixels < VlmCoordinateProtocol.GUI_PLUS_MIN_PIXELS
                    || uploadPixels > VlmCoordinateProtocol.GUI_PLUS_MAX_PIXELS
                    || upload.getWidth() % VlmCoordinateProtocol.GUI_PLUS_IMAGE_MULTIPLE != 0
                    || upload.getHeight() % VlmCoordinateProtocol.GUI_PLUS_IMAGE_MULTIPLE != 0) {
                throw new JSONException("resized screenshot is outside GUI-Plus image limits");
            }
            return new GuiPlusImage(metadata);
        } catch (IllegalArgumentException exception) {
            throw new JSONException("screenshot dimensions are outside GUI-Plus image limits");
        } finally {
            if (upload != source && upload != null) {
                upload.recycle();
            }
            source.recycle();
        }
    }

    private static JSONObject transferMetadata(JSONObject screenshot,
            int sourceWidth, int sourceHeight, int uploadWidth, int uploadHeight, String transform)
            throws JSONException {
        return new JSONObject()
                .put("screenshot_id", screenshot.optString("screenshot_id", ""))
                .put("observation_id", screenshot.optString("observation_id", ""))
                .put("observation_version", screenshot.optLong("observation_version", 0L))
                .put("capture_type", screenshot.optString("capture_type", ""))
                .put("source_width_px", sourceWidth)
                .put("source_height_px", sourceHeight)
                .put("upload_width_px", uploadWidth)
                .put("upload_height_px", uploadHeight)
                .put("image_transform", transform);
    }

    private static int positiveOrZero(int value) {
        return Math.max(0, value);
    }

    public static final class PreparedImages {
        public final JSONArray images;
        /** Safe metadata only; encoded image bytes are never placed in the task trace. */
        public final JSONArray requestMetadata;

        PreparedImages(JSONArray images, JSONArray requestMetadata) {
            this.images = images;
            this.requestMetadata = requestMetadata;
        }
    }

    private static final class GuiPlusImage {
        final JSONObject metadata;

        GuiPlusImage(JSONObject metadata) {
            this.metadata = metadata;
        }
    }
}
