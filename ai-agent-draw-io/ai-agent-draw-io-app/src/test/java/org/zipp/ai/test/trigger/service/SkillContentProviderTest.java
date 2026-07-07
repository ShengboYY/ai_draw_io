package org.zipp.ai.test.trigger.service;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;
import org.zipp.ai.trigger.http.service.SkillContentProvider;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SkillContentProviderTest {

    @Test
    public void shouldAskDrawerToLoadSharedSkillsThroughToolCallsOnce() {
        SkillContentProvider provider = new SkillContentProvider();
        ReflectionTestUtils.setField(provider, "skillCatalogService", new SkillCatalogService());

        String section = provider.buildSkillSection(List.of("drawio-xml-guide", "drawio-visual-design"), null);

        assertTrue(section.contains("[Required Skill Tool Calls]"));
        assertTrue(section.contains("get_drawio_skill"));
        assertTrue(section.contains("- drawio-xml-guide"));
        assertTrue(section.contains("- drawio-visual-design"));
        assertFalse(section.contains("XML Rules"));
        assertFalse(section.contains("House Style"));
        assertEquals(1, count(section, "- drawio-xml-guide"));
        assertEquals(1, count(section, "- drawio-visual-design"));
    }

    @Test
    public void shouldReturnRequiredSkillNamesForRunScopedToolAllowlist() {
        SkillContentProvider provider = new SkillContentProvider();
        ReflectionTestUtils.setField(provider, "skillCatalogService", new SkillCatalogService());

        SkillContentProvider.SkillSection section = provider.buildSkillSectionWithMetadata(List.of("drawio-flowchart"), null);

        assertTrue(section.requiredSkillNames().contains("drawio-xml-guide"));
        assertTrue(section.requiredSkillNames().contains("drawio-visual-design"));
        assertTrue(section.requiredSkillNames().contains("drawio-flowchart"));
        assertFalse(section.requiredSkillNames().contains("drawio-er"));
    }

    private int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
