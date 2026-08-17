package com.mojian.project;

import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.entity.StoryProject;
import com.mojian.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;

    @Test
    void createProjectReturnsCodeZeroAndStringId() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"接口测试项目\",\"genre\":\"悬疑\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        int quote1 = body.indexOf("\"id\":\"");
        assertTrue(quote1 > 0, "雪花ID应序列化为字符串，实际响应：" + body);
    }

    @Test
    void createProjectWithBlankNameRejected() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void getMissingProjectReturnsNotFoundCode() throws Exception {
        mockMvc.perform(get("/api/projects/-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void deleteProjectReturnsSuccess() throws Exception {
        ProjectSaveRequest req = new ProjectSaveRequest();
        req.setName("待删除");
        StoryProject project = projectService.create(req);

        mockMvc.perform(delete("/api/projects/" + project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
