package org.zipp.ai.domain.agent.service.armory.matter.skills.impl;

import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI Community 构建skills <a href="https://github.com/spring-ai-community/spring-ai-agent-utils">spring-ai-agent-utils</a>
 */
@Slf4j
@Service
public class DefaultToolSkillsCreateService implements ToolSkillsCreateService {

    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) throws Exception {

        String type = toolSkills.getType();
        String path = toolSkills.getPath();

        List<ToolCallback> toolCallbackList = new ArrayList<>();

        if ("directory".equals(type)){
            ToolCallback toolCallback = SkillsTool.builder()
                    .addSkillsDirectory(path)
                    .build();
            toolCallbackList.add(toolCallback);
        }

        if ("resource".equals(type)){
            org.springframework.core.io.Resource resource = new ClassPathResource(path);
            String dirPath;
            
            // 更优雅的判断方式：通过 URL 协议判断资源是否在 Jar 包中
            try {
                String protocol = resource.getURL().getProtocol();
                if ("jar".equals(protocol) || "zip".equals(protocol) || "wsjar".equals(protocol)) {
                    // 运行环境在 Jar/Zip 内部，需要提取到临时目录
                    log.info("检测到在 Jar 环境运行，提取 skills 到临时目录...");
                    ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
                    org.springframework.core.io.Resource[] resources = resolver.getResources("classpath*:" + path + "/**");
                    java.nio.file.Path tmpDir = java.nio.file.Files.createTempDirectory("agent-skills-");
                    for (org.springframework.core.io.Resource res : resources) {
                        if (res.isReadable()) {
                            String urlStr = res.getURL().toString();
                            int index = urlStr.indexOf(path);
                            if (index != -1) {
                                String relativePath = urlStr.substring(index + path.length());
                                if (relativePath.startsWith("/")) {
                                    relativePath = relativePath.substring(1);
                                }
                                java.nio.file.Path dest = tmpDir.resolve(relativePath);
                                java.nio.file.Files.createDirectories(dest.getParent());
                                java.nio.file.Files.copy(res.getInputStream(), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                // 如果是脚本文件，赋予可执行权限
                                if (dest.toString().endsWith(".sh") || dest.toString().endsWith(".py")) {
                                    dest.toFile().setExecutable(true);
                                }
                            }
                        }
                    }
                    dirPath = tmpDir.toAbsolutePath().toString();
                    log.info("Skills 提取完成，临时目录: {}", dirPath);
                } else {
                    // 运行环境在本地普通文件系统 (协议通常是 file)
                    dirPath = resource.getFile().getAbsolutePath();
                }
            } catch (Exception e) {
                throw new RuntimeException("处理 resource 技能路径失败: " + path, e);
            }

            ToolCallback toolCallback = SkillsTool.builder()
                    .addSkillsDirectory(dirPath)
                    .build();
            toolCallbackList.add(toolCallback);
        }

        return toolCallbackList.toArray(new ToolCallback[0]);
    }

}
