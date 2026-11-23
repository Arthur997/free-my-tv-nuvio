package io.freemytv;

/**
 * The home screen on Fire TV, opened over Amazon's launcher (FireTv). Unlike {@link HomeActivity}, its manifest entry
 * has no resumeWhilePausing: on Fire OS that attribute on a non-HOME activity leaves window focus on Amazon's pausing
 * launcher and drops the remote's keys (simbasjungles/free-my-tv#167).
 */
public class FireHomeActivity extends HomeActivity {}
