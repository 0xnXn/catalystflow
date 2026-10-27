package com.catalystflow.ai.service;

import com.catalystflow.ai.model.AiReviewQuestionAnswer;
import com.catalystflow.ai.model.AiReviewQuestionFeedback;
import com.catalystflow.ai.model.AiReviewRequest;
import com.catalystflow.ai.model.AiReviewResponse;
import com.catalystflow.ai.model.DisciplineInfo;
import com.catalystflow.ai.model.DisciplineSeniorityLevel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AiReviewServiceTest {

    @Inject
    AiReviewService aiReviewService;

    @Test
    void getDisciplines_returnsNonEmptyListWithLevels() {
        List<DisciplineInfo> disciplines = aiReviewService.getDisciplines();

        assertNotNull(disciplines);
        assertFalse(disciplines.isEmpty(), "Expected at least one discipline");

        for (DisciplineInfo info : disciplines) {
            assertNotNull(info.getDiscipline(), "Discipline key must not be null");
            assertNotNull(info.getLevels(), "Levels must not be null");
            assertFalse(info.getLevels().isEmpty(), "Each discipline must have at least one seniority level");
            for (DisciplineSeniorityLevel level : info.getLevels()) {
                assertNotNull(level.getKey(), "Seniority level key must not be null");
                assertNotNull(level.getDisplayName(), "Seniority level displayName must not be null");
            }
        }
    }

    @Test
    void analyze_mockMode_returnsQuestionFeedback() {
        AiReviewRequest request = new AiReviewRequest();
        request.setSeniority("Engineer");
        request.setDiscipline("Software & DevOps Engineering");
        request.setQuestions(List.of(buildQuestion("q1", "Describe your technical contributions.", "I worked on the backend API.")));

        AiReviewResponse response = aiReviewService.analyze(request);

        assertNotNull(response);
        assertNotNull(response.getSuggestions(), "suggestions must not be null");
        assertEquals(2, response.getSuggestions().size(), "mock mode should return representative question feedback");
        assertEquals(AiReviewQuestionFeedback.VerdictEnum.NEEDS_IMPROVEMENT, response.getSuggestions().get(0).getVerdict());
        assertFalse(response.getSuggestions().get(0).getSuggestions().isEmpty());
        assertEquals(AiReviewQuestionFeedback.VerdictEnum.STRONG, response.getSuggestions().get(1).getVerdict());
        assertTrue(response.getSuggestions().get(1).getSuggestions().isEmpty());
    }

    @Test
    void stackCriteria_includesInheritedLevelCriteria() {
        List<DisciplineInfo> disciplines = aiReviewService.getDisciplines();
        assertFalse(disciplines.isEmpty(), "Expected performance criteria fixture to contain disciplines");

        DisciplineInfo hierarchical = disciplines.stream()
                .filter(d -> d.getDiscipline().equals("Software & DevOps Engineering"))
                .findFirst()
                .orElse(null);
        assertNotNull(hierarchical, "Expected Software & DevOps Engineering fixture data");
        assertTrue(hierarchical.getLevels().size() >= 3, "Expected multiple seniority levels for inheritance test");

        String higherLevel = "Engineer";
        List<String> criteria = aiReviewService.stackCriteria(hierarchical.getDiscipline(), higherLevel, "technology");
        assertNotNull(criteria);

        List<String> baseCriteria = aiReviewService.stackCriteria(hierarchical.getDiscipline(), "Graduate", "technology");
        assertTrue(criteria.size() >= baseCriteria.size(),
                "Higher level should stack on top of lower level criteria");
    }

    @Test
    void stackCriteria_unknownDiscipline_throwsBadRequestException() {
        assertThrows(BadRequestException.class, () ->
                aiReviewService.stackCriteria("nonexistent-discipline", "Engineer", "technology")
        );
    }

    @Test
    void stackCriteria_unknownSeniority_throwsBadRequestException() {
        List<DisciplineInfo> disciplines = aiReviewService.getDisciplines();
        assertFalse(disciplines.isEmpty(), "Expected performance criteria fixture to contain disciplines");

        DisciplineInfo hierarchical = disciplines.stream()
                .filter(d -> d.getDiscipline().equals("Software & DevOps Engineering"))
                .findFirst()
                .orElse(null);
        assertNotNull(hierarchical, "Expected Software & DevOps Engineering fixture data");

        assertThrows(BadRequestException.class, () ->
                aiReviewService.stackCriteria(hierarchical.getDiscipline(), "NonexistentLevel", "technology")
        );
    }

    @Test
    void getDisciplines_flatDiscipline_hasSingleSentinelLevel() {
        List<DisciplineInfo> disciplines = aiReviewService.getDisciplines();
        DisciplineInfo siteLead = disciplines.stream()
                .filter(d -> d.getDiscipline().equals("Site Lead"))
                .findFirst()
                .orElse(null);

        assertNotNull(siteLead, "Expected Site Lead fixture data");
        assertEquals(1, siteLead.getLevels().size(), "Flat discipline should have exactly one sentinel level");
        assertNotNull(siteLead.getLevels().get(0).getKey());
    }

    @Test
    void stackCriteria_flatDiscipline_returnsCriteriaDirectly() {
        List<DisciplineInfo> disciplines = aiReviewService.getDisciplines();
        DisciplineInfo siteLead = disciplines.stream()
                .filter(d -> d.getDiscipline().equals("Site Lead"))
                .findFirst()
                .orElse(null);

        assertNotNull(siteLead, "Expected Site Lead fixture data");

        List<String> consulting = aiReviewService.stackCriteria("Site Lead", siteLead.getLevels().get(0).getKey(), "consulting");
        assertNotNull(consulting);
        assertFalse(consulting.isEmpty(), "Site Lead should have consulting criteria");
    }

    private AiReviewQuestionAnswer buildQuestion(String id, String text, String answer) {
        AiReviewQuestionAnswer qa = new AiReviewQuestionAnswer();
        qa.setQuestionId(id);
        qa.setQuestionText(text);
        qa.setAnswer(answer);
        return qa;
    }
}
