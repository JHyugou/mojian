package com.mojian.chapter.dto;

import lombok.Data;

@Data
public class ChapterMemorySaveRequest {

    private String summary;

    private String keyFacts;

    private String unresolvedConflicts;

    private String characterChanges;
}
