package com.orinan.api.domain.navercommerce.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class NaverProductImageValidator {
    private static final int MAX_FILES = 10;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 20 * 1024 * 1024;
    private static final int MAX_DIMENSION = 10_000;
    private static final long MAX_PIXELS = 25_000_000L;

    public List<ImageData> validate(List<MultipartFile> files) {
        if (files == null || files.isEmpty() || files.size() > MAX_FILES) {
            throw new ApiException(ApiCode.BAD_REQUEST, "상품 이미지는 1~10장 첨부해 주세요.");
        }
        // Check every declared size before allocating image bytes or opening a stream.
        long declaredTotal = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty() || file.getSize() <= 0 || file.getSize() > MAX_IMAGE_BYTES) {
                throw invalidSize();
            }
            declaredTotal += file.getSize();
            if (declaredTotal > MAX_TOTAL_BYTES) throw invalidSize();
            if (!"image/png".equals(file.getContentType()) && !"image/jpeg".equals(file.getContentType())) {
                throw invalidImage();
            }
        }

        List<ImageData> images = new ArrayList<>(files.size());
        int actualTotal = 0;
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            int remaining = MAX_TOTAL_BYTES - actualTotal;
            if (remaining <= 0) throw invalidSize();
            try (var source = file.getInputStream()) {
                // One extra byte detects dishonest multipart metadata without unbounded reads.
                byte[] bytes = source.readNBytes(Math.min(MAX_IMAGE_BYTES, remaining) + 1);
                if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES || bytes.length > remaining) {
                    throw invalidSize();
                }
                String type = validateImage(bytes, file.getContentType());
                images.add(new ImageData(bytes, type, "image-" + index + ("image/png".equals(type) ? ".png" : ".jpg")));
                actualTotal += bytes.length;
            } catch (ApiException exception) {
                throw exception;
            } catch (IOException | RuntimeException exception) {
                // Multipart and decoder failures can contain filenames, paths or raw image data.
                throw invalidImage();
            }
        }
        return List.copyOf(images);
    }

    private String validateImage(byte[] bytes, String declaredType) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                String actualType = "PNG".equalsIgnoreCase(format) ? "image/png"
                        : "JPEG".equalsIgnoreCase(format) ? "image/jpeg" : null;
                if (!declaredType.equals(actualType)) throw invalidImage();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    throw new ApiException(ApiCode.BAD_REQUEST, "상품 이미지는 가로·세로 10,000픽셀, 총 2,500만 픽셀 이하여야 합니다.");
                }
                boolean[] warning = {false};
                reader.addIIOReadWarningListener((ignored, message) -> warning[0] = true);
                var decoded = reader.read(0);
                if (decoded == null) throw invalidImage();
                try {
                    if (warning[0] || decoded.getWidth() != width || decoded.getHeight() != height) throw invalidImage();
                } finally {
                    decoded.flush();
                }
                return actualType;
            } finally {
                reader.dispose();
            }
        }
    }

    private static ApiException invalidImage() {
        return new ApiException(ApiCode.BAD_REQUEST, "파일 형식과 내용이 일치하는 PNG 또는 JPEG 상품 이미지를 첨부해 주세요.");
    }

    private static ApiException invalidSize() {
        return new ApiException(ApiCode.BAD_REQUEST, "상품 이미지는 한 장당 10MB 이하, 전체 20MB 이하로 첨부해 주세요.");
    }

    public record ImageData(byte[] bytes, String contentType, String filename) {
        @Override public String toString() { return "ImageData[REDACTED]"; }
    }
}
