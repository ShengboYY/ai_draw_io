package org.zipp.ai.ingestion.worker.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TesseractOcrEngineTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void invokesAFixedArgumentProtocolAndParsesWordBoxesFromTsv() throws Exception {
        Path executable = temporaryDirectory.resolve("fake-tesseract");
        Files.writeString(executable, "#!/bin/sh\n"
                + "printf 'level\\tpage_num\\tblock_num\\tpar_num\\tline_num\\tword_num\\tleft\\ttop\\twidth\\theight\\tconf\\ttext\\n' > \"$2.tsv\"\n"
                + "printf '5\\t1\\t1\\t1\\t1\\t1\\t10\\t5\\t30\\t10\\t92.5\\tSprint\\n' >> \"$2.tsv\"\n"
                + "printf '5\\t1\\t1\\t1\\t1\\t2\\t45\\t5\\t35\\t10\\t87.5\\tReview\\n' >> \"$2.tsv\"\n");
        Files.setPosixFilePermissions(executable, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Path image = temporaryDirectory.resolve("page with spaces.png");
        ImageIO.write(new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB), "png", image.toFile());

        var result = new TesseractOcrEngine(executable.toString(), "eng+chi_sim", Duration.ofSeconds(5))
                .recognize(image, 1);

        assertEquals("Sprint Review", result.text());
        assertEquals(0.90, result.confidence(), 0.0001);
        assertEquals(2, result.words().size());
        assertEquals(0.10, result.words().get(0).region().x1(), 0.0001);
        assertEquals("line:1:1:1", result.words().get(0).lineId());
    }
}
