package com.orinan.api.domain.aistudio.service;

import com.orinan.api.common.exception.ApiException;
import com.orinan.api.domain.aistudio.exception.AiStudioErrorCode;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.Set;

public record AiStudioImage(byte[] bytes, String contentType, int width, int height) {
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    public static AiStudioImage validate(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (!Set.of("png", "jpeg", "jpg").contains(format) || width < 1 || height < 1
                        || width > 10000 || height > 10000 || (long) width * height > 25_000_000L
                        || reader.read(0) == null) throw invalid();
                return new AiStudioImage(bytes, format.equals("png") ? "image/png" : "image/jpeg", width, height);
            } finally { reader.dispose(); }
        } catch (Exception exception) { throw invalid(); }
    }
    private static ApiException invalid() { return new ApiException(AiStudioErrorCode.PROVIDER_FAILURE); }
    @Override public String toString() { return "AiStudioImage[" + contentType + ", " + width + "x" + height + "]"; }
}
