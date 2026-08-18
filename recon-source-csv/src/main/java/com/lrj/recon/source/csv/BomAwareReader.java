package com.lrj.recon.source.csv;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;

/** 打开文件并识别/剥离 UTF-8、UTF-16 和 UTF-32 BOM，避免 BOM 污染首列表头。 */
final class BomAwareReader {

    private BomAwareReader() {
    }

    static Reader open(CsvSourceConfig config) throws IOException {
        BufferedInputStream input = new BufferedInputStream(Files.newInputStream(config.path));
        try {
            input.mark(4);
            byte[] prefix = input.readNBytes(4);
            Bom bom = Bom.detect(prefix);
            input.reset();

            Charset effective = config.charset;
            if (bom != null) {
                if (config.charsetExplicit && !compatible(config.charset, bom.charset)) {
                    throw new IllegalArgumentException("CSV BOM charset " + bom.charset.name()
                            + " conflicts with configured charset " + config.charset.name()
                            + " for " + config.path);
                }
                skipFully(input, bom.length);
                effective = bom.charset;
            }
            return new InputStreamReader(input, effective.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT));
        } catch (RuntimeException | IOException failure) {
            input.close();
            throw failure;
        }
    }

    private static boolean compatible(Charset configured, Charset bom) {
        String configuredName = configured.name();
        String bomName = bom.name();
        if (configuredName.equalsIgnoreCase(bomName)) {
            return true;
        }
        return (configuredName.equalsIgnoreCase("UTF-16") && bomName.startsWith("UTF-16"))
                || (configuredName.equalsIgnoreCase("UTF-32") && bomName.startsWith("UTF-32"));
    }

    private static void skipFully(BufferedInputStream input, int count) throws IOException {
        int remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                throw new IOException("unable to skip CSV BOM");
            }
            remaining -= (int) skipped;
        }
    }

    private record Bom(int length, Charset charset) {
        static Bom detect(byte[] bytes) {
            if (startsWith(bytes, 0x00, 0x00, 0xFE, 0xFF)) {
                return new Bom(4, Charset.forName("UTF-32BE"));
            }
            if (startsWith(bytes, 0xFF, 0xFE, 0x00, 0x00)) {
                return new Bom(4, Charset.forName("UTF-32LE"));
            }
            if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
                return new Bom(3, Charset.forName("UTF-8"));
            }
            if (startsWith(bytes, 0xFE, 0xFF)) {
                return new Bom(2, Charset.forName("UTF-16BE"));
            }
            if (startsWith(bytes, 0xFF, 0xFE)) {
                return new Bom(2, Charset.forName("UTF-16LE"));
            }
            return null;
        }

        private static boolean startsWith(byte[] actual, int... expected) {
            if (actual.length < expected.length) {
                return false;
            }
            for (int i = 0; i < expected.length; i++) {
                if (Byte.toUnsignedInt(actual[i]) != expected[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
