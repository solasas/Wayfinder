package com.sashank.map_shortest_path_finder.exception;

/**
 * The request is understandable as a routing request but too ambiguous or self-contradictory
 * to act on (vague destination, "fastest and shortest", ...). Carries one short question for
 * the user; maps to 422 so a client can show the question and let the user try again.
 */
public class ClarificationNeededException extends RuntimeException {

    private final String question;

    public ClarificationNeededException(String question) {
        super(question);
        this.question = question;
    }

    public String getQuestion() { return question; }
}
