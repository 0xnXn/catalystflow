package com.catalystflow.ai.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Peer360DigestTest {

    private static Peer360Digest valid() {
        Peer360Digest d = new Peer360Digest();
        Peer360Digest.Theme t = new Peer360Digest.Theme();
        t.theme = "Delivery leadership";
        t.summary = "Strong ownership across projects.";
        d.themes = List.of(t);
        Peer360Digest.Point c = new Peer360Digest.Point();
        c.point = "Owns Dynamite delivery end-to-end.";
        c.reviewIds = List.of("r-1", "r-2");
        d.consensus = List.of(c);
        d.dissent = new ArrayList<>();
        d.overallSentiment = "positive";
        return d;
    }

    @Test
    void validate_fullyPopulated_returnsNoErrors() {
        assertTrue(valid().validate().isEmpty());
    }

    @Test
    void validate_emptyArrays_areAcceptable() {
        Peer360Digest d = new Peer360Digest();
        d.themes = List.of();
        d.consensus = List.of();
        d.dissent = List.of();
        d.overallSentiment = "mixed";
        assertTrue(d.validate().isEmpty());
    }

    @Test
    void validate_nullArrays_flagFields() {
        Peer360Digest d = new Peer360Digest();
        d.overallSentiment = "positive";
        List<String> errors = d.validate();
        assertEquals(3, errors.size());
        assertTrue(errors.stream().anyMatch(e -> e.contains("themes")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("consensus")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("dissent")));
    }

    @Test
    void validate_invalidSentiment_flagsField() {
        Peer360Digest d = valid();
        d.overallSentiment = "extremely-positive";
        assertTrue(d.validate().stream().anyMatch(e -> e.contains("overallSentiment")));
    }

    @Test
    void validate_blankThemeLabel_flagsRow() {
        Peer360Digest d = valid();
        Peer360Digest.Theme bad = new Peer360Digest.Theme();
        bad.theme = "  ";
        d.themes = List.of(bad);
        assertTrue(d.validate().stream().anyMatch(e -> e.contains("themes[0].theme")));
    }

    @Test
    void validate_blankConsensusPoint_flagsRow() {
        Peer360Digest d = valid();
        Peer360Digest.Point bad = new Peer360Digest.Point();
        d.consensus = List.of(bad);
        assertTrue(d.validate().stream().anyMatch(e -> e.contains("consensus[0].point")));
    }
}
