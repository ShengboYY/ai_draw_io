package org.zipp.ai.ingestion.worker.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TesseractInstallationVerifierTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void verifiesDeclaredRuntimeAndEveryRequiredLanguage() throws Exception {
        Path executable = fakeTesseract();

        assertDoesNotThrow(() -> TesseractInstallationVerifier.verify(executable.toString(),
                "eng+chi_sim", "tesseract-4.1.1", Duration.ofSeconds(2)));
        assertThrows(IllegalStateException.class, () -> TesseractInstallationVerifier.verify(executable.toString(),
                "eng+chi_sim", "tesseract-5.0.0", Duration.ofSeconds(2)));
        assertThrows(IllegalStateException.class, () -> TesseractInstallationVerifier.verify(executable.toString(),
                "eng+deu", "tesseract-4.1.1", Duration.ofSeconds(2)));
    }

    private Path fakeTesseract() throws Exception {
        Path executable = temporaryDirectory.resolve("fake-tesseract");
        Files.writeString(executable, "#!/bin/sh\n"
                + "if [ \"$1\" = \"--version\" ]; then printf 'tesseract 4.1.1\\n'; exit 0; fi\n"
                + "if [ \"$1\" = \"--list-langs\" ]; then printf 'List of available languages (3):\\neng\\nchi_sim\\nosd\\n'; exit 0; fi\n"
                + "exit 2\n");
        Files.setPosixFilePermissions(executable, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return executable;
    }
}
