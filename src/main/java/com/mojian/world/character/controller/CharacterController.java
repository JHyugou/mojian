package com.mojian.world.character.controller;

import com.mojian.common.api.ApiResponse;
import com.mojian.world.character.dto.CharacterSaveRequest;
import com.mojian.world.character.dto.DialogueExampleDraftRequest;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.service.CharacterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CharacterController {

    private final CharacterService characterService;

    @GetMapping("/projects/{projectId}/characters")
    public ApiResponse<List<CharacterProfile>> list(@PathVariable Long projectId) {
        return ApiResponse.ok(characterService.list(projectId));
    }

    @PostMapping("/projects/{projectId}/characters")
    public ApiResponse<CharacterProfile> create(@PathVariable Long projectId,
                                                @Valid @RequestBody CharacterSaveRequest request) {
        return ApiResponse.ok(characterService.create(projectId, request));
    }

    @PutMapping("/characters/{characterId}")
    public ApiResponse<CharacterProfile> update(@PathVariable Long characterId,
                                                @Valid @RequestBody CharacterSaveRequest request) {
        return ApiResponse.ok(characterService.update(characterId, request));
    }

    @DeleteMapping("/characters/{characterId}")
    public ApiResponse<Void> delete(@PathVariable Long characterId) {
        characterService.delete(characterId);
        return ApiResponse.ok();
    }

    @PostMapping("/characters/{characterId}/dialogue-example-drafts")
    public ApiResponse<String> draftDialogueExample(@PathVariable Long characterId,
                                                    @Valid @RequestBody(required = false) DialogueExampleDraftRequest request) {
        String hint = request == null ? null : request.getHint();
        return ApiResponse.ok(characterService.draftDialogueExample(characterId, hint));
    }
}
