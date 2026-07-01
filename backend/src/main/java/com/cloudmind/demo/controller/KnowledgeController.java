package com.cloudmind.demo.controller;

import com.cloudmind.demo.dto.KnowledgeQuestionRequest;
import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.service.AuthService;
import com.cloudmind.demo.service.KnowledgeQaService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final AuthService authService;
    private final KnowledgeQaService knowledgeQaService;

    public KnowledgeController(AuthService authService, KnowledgeQaService knowledgeQaService) {
        this.authService = authService;
        this.knowledgeQaService = knowledgeQaService;
    }

    @GetMapping("/sources")
    public Map<String, Object> sources(@RequestHeader(value = "X-Token", required = false) String token) {
        AppUser user = authService.requireUser(token);
        return Map.of(
                "success", true,
                "data", knowledgeQaService.sources(user)
        );
    }

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestHeader(value = "X-Token", required = false) String token,
                                   @RequestBody KnowledgeQuestionRequest request) {
        AppUser user = authService.requireUser(token);
        return Map.of(
                "success", true,
                "message", "知识库问答完成",
                "data", knowledgeQaService.ask(
                        user,
                        request == null ? null : request.getQuestion(),
                        request == null ? null : request.getTopK(),
                        request == null ? null : request.getScopeType(),
                        request == null ? null : request.getScopeId(),
                        request == null ? null : request.getScopeIds()
                )
        );
    }

    @PostMapping("/overview")
    public Map<String, Object> overview(@RequestHeader(value = "X-Token", required = false) String token,
                                        @RequestBody KnowledgeQuestionRequest request) {
        AppUser user = authService.requireUser(token);
        return Map.of(
                "success", true,
                "message", "知识库概述生成完成",
                "data", knowledgeQaService.overview(
                        user,
                        request == null ? null : request.getTopK(),
                        request == null ? null : request.getScopeType(),
                        request == null ? null : request.getScopeId(),
                        request == null ? null : request.getScopeIds()
                )
        );
    }
}
