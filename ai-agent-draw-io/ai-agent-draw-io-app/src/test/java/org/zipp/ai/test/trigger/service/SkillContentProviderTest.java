package org.zipp.ai.test.trigger.service;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;
import org.zipp.ai.trigger.http.service.SkillContentProvider;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SkillContentProviderTest {

    @Test
    public void shouldInjectSharedXmlGuideAndVisualDesignOnce() {
        SkillContentProvider provider = new SkillContentProvider();
        ReflectionTestUtils.setField(provider, "skillCatalogService", new SkillCatalogService());

        String section = provider.buildSkillSection(List.of("drawio-xml-guide", "drawio-visual-design"), null);

        assertTrue(section.contains("[Skill Rules: drawio-xml-guide]"));
        assertTrue(section.contains("[Skill Rules: drawio-visual-design]"));
        assertTrue(section.contains("XML Rules"));
        assertTrue(section.contains("House Style"));
        assertEquals(1, count(section, "[Skill Rules: drawio-xml-guide]"));
        assertEquals(1, count(section, "[Skill Rules: drawio-visual-design]"));
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
