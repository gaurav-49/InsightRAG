package com.insightrag.eval;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import com.insightrag.common.ApiException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-14. Admin scope. */
@RestController
@RequestMapping("/api/v1/eval")
public class EvalController {

    private final EvalService service;
    private final EvalRepository repo;

    public EvalController(EvalService service, EvalRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    public record LabelledQuestion(@NotBlank String key, @NotBlank String question, String category,
                                   List<String> documents, List<String> evidence, String notes) {
    }

    public record QuestionSet(@NotEmpty List<@Valid LabelledQuestion> questions) {
    }

    /** Load or update the ground-truth set (upsert by key; eval/questions.json format). */
    @PutMapping("/questions")
    public Map<String, Object> importQuestions(@Valid @RequestBody QuestionSet set) {
        return Map.of("upserted", repo.upsert(set.questions()), "total", repo.questions().size());
    }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody(required = false) EvalService.RunOptions options) {
        return service.run(options == null ? new EvalService.RunOptions(null, null, null, true) : options);
    }

    @GetMapping("/runs/latest")
    public Map<String, Object> latest() {
        return repo.latestRun().orElseThrow(() -> ApiException.notFound("Evaluation run"));
    }
}
