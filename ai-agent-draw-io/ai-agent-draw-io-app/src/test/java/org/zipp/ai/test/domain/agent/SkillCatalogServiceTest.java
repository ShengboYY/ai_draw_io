package org.zipp.ai.test.domain.agent;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillStore;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
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
                schemaVersion: 1
                category: drawio-design
                diagramType: architecture
                selectable: true
                ---

                # Custom Architecture

                ## When To Use [P0]
                Use this custom architecture body.
                """);

        SkillCatalogService service = serviceWithExternalDir(skillsRoot);

        String catalog = service.catalogText(null);

        assertTrue(catalog.contains("- custom-architecture: Draw.io custom architecture skill. Use for service topology and gateway diagrams."));
        assertFalse(catalog.contains("- custom-architecture: >"));
    }

    @Test
    public void shouldParseNormalizedTopLevelMetadataForRouterCatalog() throws Exception {
        File skillsRoot = temporaryFolder.newFolder("skills");
        writeSkill(skillsRoot, "normalized-flowchart", """
                ---
                name: normalized-flowchart
                description: Normalized draw.io flowchart skill.
                schemaVersion: 1
                category: drawio-design
                diagramType: flowchart
                selectable: true
                metadata:
                  author: test
                ---

                # Normalized Flowchart

                ## When To Use [P0]
                Use for normalized flowcharts.
                """);

        SkillCatalogService service = serviceWithExternalDir(skillsRoot);

        String catalog = service.catalogText(null);

        assertTrue(catalog.contains("- normalized-flowchart: Normalized draw.io flowchart skill."));
    }

    @Test
    public void shouldOnlyExposeSelectableDrawioSkillsToRouterCatalog() throws Exception {
        File skillsRoot = temporaryFolder.newFolder("skills");
        writeSkill(skillsRoot, "custom-flowchart", """
                ---
                name: custom-flowchart
                description: Custom draw.io flowchart skill.
                schemaVersion: 1
                category: drawio-design
                diagramType: flowchart
                selectable: true
                ---

                # Custom Flowchart

                ## When To Use [P0]
                Use this custom flowchart body.
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
                schemaVersion: 1
                category: drawio-design
                diagramType: flowchart
                selectable: false
                ---

                # Hidden Drawio

                ## When To Use [P0]
                Use only while drafting.
                """);

        SkillCatalogService service = serviceWithExternalDir(skillsRoot);

        String catalog = service.catalogText(null);

        assertTrue(catalog.contains("- custom-flowchart: Custom draw.io flowchart skill."));
        assertFalse(catalog.contains("- custom-doc:"));
        assertFalse(catalog.contains("- hidden-drawio:"));
        assertFalse(catalog.contains("- drawio-xml-guide:"));
        assertFalse(catalog.contains("- pdf:"));
        assertFalse(catalog.contains("- battle-plan:"));
    }

    @Test
    public void shouldExposeStrongSkillMetadataAndReferenceBody() throws Exception {
        File skillsRoot = temporaryFolder.newFolder("skills");
        writeSkill(skillsRoot, "reference-flowchart", """
                ---
                name: reference-flowchart
                description: Flowchart skill with long reference material.
                schemaVersion: 1
                category: drawio-design
                diagramType: flowchart
                selectable: true
                ---

                # Reference Flowchart

                ## When To Use [P0]
                Use when the user asks for a process flow.
                """);
        writeReference(skillsRoot, "reference-flowchart", """
                # Reference Flowchart Details

                ## Golden Example [P0]
                Long XML example.
                """);

        SkillCatalogService service = serviceWithExternalDir(skillsRoot);

        SkillCatalogService.SkillInfo info = service.selectableSkills(null).stream()
                .filter(skill -> "reference-flowchart".equals(skill.name()))
                .findFirst()
                .orElseThrow();

        assertEquals(1, info.schemaVersion());
        assertEquals("flowchart", info.diagramType());
        assertEquals(SkillCatalogService.SkillSource.EXTERNAL_DIR, info.source());
        assertTrue(info.validationErrors().isEmpty());
        assertTrue(service.referenceBody("reference-flowchart", null).contains("Long XML example."));
    }

    @Test
    public void shouldBuildOneRuntimeCatalogWithSelectableAndRequiredSharedSkills() throws Exception {
        File skillsRoot = temporaryFolder.newFolder("skills");
        writeSkill(skillsRoot, "custom-runtime-flow", """
                ---
                name: custom-runtime-flow
                description: Runtime flow skill.
                schemaVersion: 1
                category: drawio-design
                diagramType: flowchart
                selectable: true
                ---

                # Runtime Flow

                ## Rules [P0]
                Use the runtime flow rules.
                """);

        SkillCatalogService.RuntimeCatalog runtime =
                serviceWithExternalDir(skillsRoot).runtimeCatalog("owner-1");

        assertTrue(runtime.selectableSkills().stream()
                .anyMatch(skill -> "custom-runtime-flow".equals(skill.name())));
        assertEquals(
                List.of("drawio-xml-guide", "drawio-visual-design"),
                runtime.sharedSkills().stream().map(SkillCatalogService.SkillInfo::name).toList());
    }

    @Test
    public void shouldKeepInvalidExternalDrawioSkillOutOfRouterCatalog() throws Exception {
        File skillsRoot = temporaryFolder.newFolder("skills");
        writeSkill(skillsRoot, "broken-flowchart", """
                ---
                name: broken-flowchart
                description: Missing normalized schema and section priorities.
                category: drawio-design
                selectable: true
                ---

                # Broken Flowchart

                ## Rules
                This heading is not prioritized.
                """);

        SkillCatalogService service = serviceWithExternalDir(skillsRoot);

        assertFalse(service.catalogText(null).contains("- broken-flowchart:"));
        SkillCatalogService.SkillInfo info = service.info("broken-flowchart", null);
        assertFalse(info.validationErrors().isEmpty());
    }

    @Test
    public void shouldKeepInvalidDbDrawioSkillOutOfRouterCatalog() {
        SkillCatalogService service = new SkillCatalogService();
        ReflectionTestUtils.setField(service, "skillStore", new FakeSkillStore(List.of(
                new SkillStore.StoredSkill("", "broken-db", "Broken DB skill", "drawio-design",
                        """
                                # Broken DB Skill

                                ## Rules
                                Missing schema metadata.
                                """,
                        SkillStore.Visibility.PUBLIC, true))));

        assertFalse(service.catalogText(null).contains("- broken-db:"));
        SkillCatalogService.SkillInfo info = service.info("broken-db", null);
        assertFalse(info.validationErrors().isEmpty());
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

    private void writeReference(File skillsRoot, String name, String content) throws Exception {
        File skillDir = new File(skillsRoot, name);
        Files.writeString(new File(skillDir, "reference.md").toPath(), content, StandardCharsets.UTF_8);
    }

    private record FakeSkillStore(List<SkillStore.StoredSkill> publicSkills) implements SkillStore {
        @Override
        public List<StoredSkill> listPublic() {
            return publicSkills;
        }

        @Override
        public List<StoredSkill> listByOwner(String ownerId) {
            return List.of();
        }

        @Override
        public void upsert(StoredSkill skill) {
        }

        @Override
        public void seedIfAbsent(StoredSkill skill) {
        }

        @Override
        public void delete(String ownerId, String name) {
        }
    }
}
