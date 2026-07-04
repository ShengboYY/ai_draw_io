package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.service.intent.DiagramTypeClassifier;

import static org.junit.Assert.assertEquals;

public class DiagramTypeClassifierTest {

    private final DiagramTypeClassifier classifier = new DiagramTypeClassifier();

    @Test
    public void shouldClassifyBlankCanvasBeforeOtherTypes() {
        assertEquals("blank", classifier.classify("打开空画布"));
        assertEquals("blank", classifier.classify("blank canvas for a flowchart later"));
    }

    @Test
    public void shouldClassifyStructuredDiagramTypes() {
        assertEquals("flowchart", classifier.classify("画一个审批流程"));
        assertEquals("architecture", classifier.classify("设计微服务部署架构"));
        assertEquals("architecture", classifier.classify("jvm架构图"));
        assertEquals("uml", classifier.classify("生成 UML class diagram"));
        assertEquals("sequence", classifier.classify("画调用链时序图"));
        assertEquals("er", classifier.classify("数据库表 schema entity 关系"));
        assertEquals("usecase", classifier.classify("用例图包含 actor"));
        assertEquals("state", classifier.classify("状态生命周期 state diagram"));
        assertEquals("mindmap", classifier.classify("产品概念图 mindmap"));
    }

    @Test
    public void shouldClassifyIllustrationAfterStructuredTypes() {
        assertEquals("illustration", classifier.classify("画只可爱的小猫"));
        assertEquals("illustration", classifier.classify("draw a cute dog"));
        assertEquals("flowchart", classifier.classify("draw a checkout flowchart"));
    }

    @Test
    public void shouldFallbackToOthers() {
        assertEquals("others", classifier.classify("帮我画一下这个想法"));
        assertEquals("others", classifier.classify(null));
    }

}
