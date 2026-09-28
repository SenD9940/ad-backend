package com.orinan.api.domain.navercommerce.service;

import com.orinan.api.common.code.ApiCode;
import com.orinan.api.common.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaverProductImageValidatorTest {
    private static final int TEN_MIB = 10 * 1024 * 1024;
    private final NaverProductImageValidator validator = new NaverProductImageValidator();

    @Test void validatesActualPngAndJpegInOrderAndNeverUsesOriginalFilenames() throws Exception {
        byte[] png = image("png",2,3);
        byte[] jpeg = image("jpeg",3,2);
        var output = validator.validate(List.of(
                new MockMultipartFile("images","../../private-customer-file.exe","image/png",png),
                new MockMultipartFile("images","private-customer-image.png","image/jpeg",jpeg)));
        assertThat(output).hasSize(2);
        assertThat(output.get(0).bytes()).isEqualTo(png);
        assertThat(output.get(0).contentType()).isEqualTo("image/png");
        assertThat(output.get(0).filename()).isEqualTo("image-0.png");
        assertThat(output.get(1).bytes()).isEqualTo(jpeg);
        assertThat(output.get(1).contentType()).isEqualTo("image/jpeg");
        assertThat(output.get(1).filename()).isEqualTo("image-1.jpg");
        assertThat(output.toString()).doesNotContain("private-customer", "image/png", "image/jpeg", "image-0.png");
        assertThatThrownBy(() -> output.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void acceptsExactlyTenFilesAndRejectsMissingEmptyAndElevenFilesBeforeReading() throws Exception {
        var file = new MockMultipartFile("images","untrusted.png","image/png",image("png",1,1));
        List<MultipartFile> ten = new ArrayList<>();
        for(int i=0;i<10;i++)ten.add(file);
        assertThat(validator.validate(ten)).hasSize(10).last().extracting(NaverProductImageValidator.ImageData::filename)
                .isEqualTo("image-9.png");
        assertBadRequest(() -> validator.validate(null));
        assertBadRequest(() -> validator.validate(List.of()));
        var untouched=mock(MultipartFile.class);
        List<MultipartFile> eleven = new ArrayList<>();
        for(int i=0;i<11;i++)eleven.add(untouched);
        assertBadRequest(() -> validator.validate(eleven));
        verifyNoInteractions(untouched);
    }

    @Test void rejectsNullEmptyNegativeAndOversizeDeclarationsWithoutAllocatingBytes() throws Exception {
        assertBadRequest(() -> validator.validate(Arrays.asList((MultipartFile)null)));
        assertBadRequest(() -> validator.validate(List.of(new MockMultipartFile("images",new byte[0]))));
        for(long size:new long[]{0,-1,TEN_MIB+1L,Long.MAX_VALUE}) {
            var file=declared(size,"image/png");
            assertBadRequest(() -> validator.validate(List.of(file)));
            verify(file,never()).getInputStream(); verify(file,never()).getBytes();
        }
    }

    @Test void rejectsEntireDeclaredBatchAboveTwentyMiBBeforeOpeningAnyImage() throws Exception {
        var first=declared(7L*1024*1024,"image/png");
        var second=declared(7L*1024*1024,"image/png");
        var third=declared(7L*1024*1024,"image/png");
        assertBadRequest(() -> validator.validate(List.of(first,second,third)));
        for(var file:List.of(first,second,third)) {
            verify(file,never()).getInputStream(); verify(file,never()).getBytes();
        }
    }

    @Test void rejectsLaterFileMimeBeforeAllocatingEarlierValidImage() throws Exception {
        var valid=declared(100,"image/png");
        var invalid=declared(100,"image/svg+xml");
        assertBadRequest(() -> validator.validate(List.of(valid,invalid)));
        verify(valid,never()).getInputStream(); verify(invalid,never()).getInputStream();
    }

    @Test void acceptsExactTenMiBPerFileAndTwentyMiBCombinedLimit() throws Exception {
        byte[] png = paddedPng(TEN_MIB);
        var files=List.<MultipartFile>of(new MockMultipartFile("images","one.png","image/png",png),
                new MockMultipartFile("images","two.png","image/png",png));
        var output=validator.validate(files);
        assertThat(output).hasSize(2);
        assertThat(output.stream().mapToLong(i->i.bytes().length).sum()).isEqualTo(20L*1024*1024);
    }

    @Test void boundsActualReadToTenMiBPlusOneAgainstFalseMultipartLength() throws Exception {
        var file=declared(1,"image/png");
        var stream=new ByteArrayInputStream(new byte[TEN_MIB+500]);
        when(file.getInputStream()).thenReturn(stream);
        assertBadRequest(() -> validator.validate(List.of(file)));
        assertThat(stream.available()).isEqualTo(499);
        verify(file,never()).getBytes();
    }

    @Test void enforcesActualAggregateBudgetWhenDeclaredLengthsUnderstateContent() throws Exception {
        byte[] png=paddedPng(TEN_MIB);
        var first=declared(1,"image/png");var second=declared(1,"image/png");var third=declared(1,"image/png");
        when(first.getInputStream()).thenReturn(new ByteArrayInputStream(png));
        when(second.getInputStream()).thenReturn(new ByteArrayInputStream(png));
        assertBadRequest(() -> validator.validate(List.of(first,second,third)));
        verify(third,never()).getInputStream();
    }

    @Test void rejectsUnsupportedMimesMismatchedActualFormatAndSpoofedHeaders() throws Exception {
        byte[] png=image("png",2,2);
        for(var file:new MultipartFile[]{
                new MockMultipartFile("images","fake.jpg","image/jpeg",png),
                new MockMultipartFile("images","fake.png","image/png",image("jpeg",2,2)),
                new MockMultipartFile("images","fake.png","image/png","not-an-image".getBytes(StandardCharsets.UTF_8)),
                new MockMultipartFile("images","fake.png","image/png",image("gif",2,2)),
                new MockMultipartFile("images","fake.svg","image/svg+xml","<svg/>".getBytes(StandardCharsets.UTF_8)),
                new MockMultipartFile("images","fake.png",null,png)}) {
            assertBadRequest(() -> validator.validate(List.of(file)));
        }
    }

    @Test void fullyDecodesAndRejectsTruncatedPngAndJpegDespiteReadableHeaders() throws Exception {
        byte[] png=image("png",2,2);byte[] jpeg=image("jpeg",2,2);
        for(var file:List.of(
                new MockMultipartFile("images","truncated.png","image/png",Arrays.copyOf(png,33)),
                new MockMultipartFile("images","truncated.jpg","image/jpeg",Arrays.copyOf(jpeg,jpeg.length-2)))) {
            assertBadRequest(() -> validator.validate(List.of(file)));
        }
    }

    @Test void rejectsExcessDimensionsAndPixelAreaFromHeaderBeforeDecoding() throws Exception {
        for(int[] dimensions:new int[][]{{10001,1},{1,10001},{5001,5000},{Integer.MAX_VALUE,Integer.MAX_VALUE}}) {
            byte[] png=image("png",2,2);
            ByteBuffer.wrap(png).putInt(16,dimensions[0]).putInt(20,dimensions[1]);
            var crc=new CRC32();crc.update(png,12,17);ByteBuffer.wrap(png).putInt(29,(int)crc.getValue());
            assertBadRequest(() -> validator.validate(List.of(new MockMultipartFile("images","huge.png","image/png",png))));
        }
    }

    @Test void acceptsExactDimensionLimitWithActualDecodableImage() throws Exception {
        var file=new MockMultipartFile("images","wide.png","image/png",image("png",10000,1));
        assertThat(validator.validate(List.of(file))).hasSize(1);
    }

    @Test void streamErrorsNeverExposeOriginalFilenamePathOrExceptionCause() throws Exception {
        var file=declared(1,"image/png");
        when(file.getInputStream()).thenThrow(new IOException("/private/customer/secret-image.png"));
        assertThatThrownBy(() -> validator.validate(List.of(file))).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST))
                .hasNoCause().hasMessageNotContaining("/private/customer/secret-image.png");
        verify(file,never()).getOriginalFilename();
    }

    private static MultipartFile declared(long size,String type) {
        var file=mock(MultipartFile.class);when(file.getSize()).thenReturn(size);when(file.getContentType()).thenReturn(type);return file;
    }
    private static byte[] image(String format,int width,int height) throws IOException {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        try(var output=new ByteArrayOutputStream()) {
            assertThat(ImageIO.write(image,format,output)).isTrue();return output.toByteArray();
        } finally {image.flush();}
    }
    private static byte[] paddedPng(int length) throws IOException {
        byte[] original=image("png",2,2);
        // A legal unknown ancillary PNG chunk keeps the image valid at the exact byte boundary.
        int dataLength=length-original.length-12;
        var output=ByteBuffer.allocate(length);
        output.put(original,0,original.length-12).putInt(dataLength);
        int chunkStart=output.position();output.put("npAD".getBytes(StandardCharsets.US_ASCII));
        output.position(output.position()+dataLength);
        var crc=new CRC32();crc.update(output.array(),chunkStart,dataLength+4);
        output.putInt((int)crc.getValue()).put(original,original.length-12,12);
        return output.array();
    }
    private static void assertBadRequest(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getCodeIfs()).isEqualTo(ApiCode.BAD_REQUEST)).hasNoCause();
    }
}
