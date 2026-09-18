package com.insightrag.llm;

import java.util.function.Consumer;

import com.insightrag.prompt.GroundedPrompt;

/**
 * Answer synthesis behind one interface (§11, "Provider API change"): the rest of the system
 * never sees a vendor type. Implementations throw {@link LlmException.Transient} for failures
 * worth retrying and {@link LlmException.Permanent} otherwise; they never retry themselves.
 */
public interface GenerationProvider {

    String name();

    Generation generate(GroundedPrompt prompt);

    /** Streams text deltas to {@code onDelta} and returns the completed generation. */
    Generation stream(GroundedPrompt prompt, Consumer<String> onDelta);
}
