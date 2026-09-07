package com.baiyu.agent.orchestrator;

import com.baiyu.agent.agent.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParallelStrategyTest {

    private ParallelStrategy strategy;
    private Map<String, Agent> agents;

    @BeforeEach
    void setUp() {
        agents = new LinkedHashMap<>();
        Agent agentA = mock(Agent.class);
        when(agentA.execute(anyString(), anyList())).thenReturn("result A");
        Agent agentB = mock(Agent.class);
        when(agentB.execute(anyString(), anyList())).thenReturn("result B");
        agents.put("a", agentA);
        agents.put("b", agentB);
        strategy = new ParallelStrategy(agents);
        org.springframework.test.util.ReflectionTestUtils.setField(strategy, "timeoutSeconds", 10);
    }

    @AfterEach
    void tearDown() {
        // Executor is internal; it uses daemon threads so JVM cleanup is safe
    }

    @Test
    void executesInParallel() {
        Map<String, String> results = strategy.execute("input", Collections.emptyList(), List.of("a", "b"));
        assertEquals("result A", results.get("a"));
        assertEquals("result B", results.get("b"));
    }

    @Test
    void agentNotFoundSkipped() {
        Map<String, String> results = strategy.execute("input", Collections.emptyList(), List.of("nonexistent", "a"));
        assertFalse(results.containsKey("nonexistent"));
        assertEquals("result A", results.get("a"));
    }

    @Test
    void agentExceptionIsolated() {
        Agent failingAgent = mock(Agent.class);
        when(failingAgent.execute(anyString(), anyList())).thenThrow(new RuntimeException("boom"));
        agents.put("fail", failingAgent);
        Map<String, String> results = strategy.execute("input", Collections.emptyList(), List.of("fail", "a"));
        assertTrue(results.get("fail").contains("error"));
        assertEquals("result A", results.get("a"));
    }

    @Test
    void getName() {
        assertEquals("parallel", strategy.getName());
    }
}
