package org.zipp.ai.test.domain.agent;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SkillCatalogServiceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldParseMultilineYamlDescriptionForRouterCatalog() throws Exception {
        File skillsRoot = temporaryFolder.newFolder("skills");
        writeSkill(skillsRoot, "custom-architecture", """
                ---
                name: custom-architecture
                description: >
                  Draw.io custom architecture skill.
                  Use for service topology and gateway diagrams.
                metadata:
                  category: drawio-design
                  selectable: true
                ---

                # Custom Architecture

                Use this custom architecture body.
                """);

        SkillCatalogService service = serviceWithExternalDir(skillsRoot);

        String catalog = service.catalogText();

        assertTrue(catalog.contains("- custom-architecture: Draw.io custom architecture skill. Use for service topology and gateway diagrams."));
        assertFalse(catalog.contains("- custom-architecture: >"));
    }

    @Test
    public void shouldOnlyExposeSelectableDrawioSkillsToRouterCatalog() throws Exception {
        File skillsRoot = temporaryFolder.newFolder("skills");
        writeSkill(skillsRoot, "custom-flowchart", """
                ---
                name: custom-flowchart
                description: Custom draw.io flowchart skill.
                metadata:
                  category: drawio-design
                  selectable: true
                ---

                # Custom Flowchart
                """);
        writeSkill(skillsRoot, "custom-doc", """
                ---
                name: custom-doc
                description: Documentation skill that must not be offered to draw.io routing.
                metadata:
                  category: docs
                  selectable: true
                ---

                # Custom Doc
                """);
        writeSkill(skillsRoot, "hidden-drawio", """
                ---
                name: hidden-drawio
                description: Draw.io draft skill that is intentionally hidden from routing.
                metadata:
                  category: drawio-design
                  selectable: false
                ---

                # Hidden Drawio
                """);

        SkillCatalogService service = serviceWithExternalDir(skillsRoot);

        String catalog = service.catalogText();

        assertTrue(catalog.contains("- custom-flowchart: Custom draw.io flowchart skill."));
        assertFalse(catalog.contains("- custom-doc:"));
        assertFalse(catalog.contains("- hidden-drawio:"));
        assertFalse(catalog.contains("- pdf:"));
        assertFalse(catalog.contains("- battle-plan:"));
    }

    private SkillCatalogService serviceWithExternalDir(File skillsRoot) {
        SkillCatalogService service = new SkillCatalogService();
        ReflectionTestUtils.setField(service, "externalSkillsDir", skillsRoot.getAbsolutePath());
        return service;
    }

    private void writeSkill(File skillsRoot, String name, String content) throws Exception {
        File skillDir = new File(skillsRoot, name);
        assertTrue(skillDir.mkdirs());
        Files.writeString(new File(skillDir, "SKILL.md").toPath(), content, StandardCharsets.UTF_8);
    }
}
