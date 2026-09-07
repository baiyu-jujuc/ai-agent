package com.baiyu.agent.orchestrator;

import com.baiyu.agent.agent.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SequentialStrategyTest {

    private SequentialStrategy strategy;
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
        strategy = new SequentialStrategy(agents);
    }

    @Test
    void executesInSequence() {
        Map<String, String> results = strategy.execute("input", Collections.emptyList(), List.of("a", "b"));
        assertEquals("result A", results.get("a"));
        assertEquals("result B", results.get("b"));
    }

    @Test
    void passesFirstResultToSecondAgent() {
        Agent agentA = agents.get("a");
        Agent agentB = agents.get("b");
        strategy.execute("start", Collections.emptyList(), List.of("a", "b"));
        verify(agentA).execute(eq("start"), anyList());
        verify(agentB).execute(eq("result A"), anyList());
    }

    @Test
    void agentNotFound() {
        Map<String, String> results = strategy.execute("input", Collections.emptyList(), List.of("nonexistent"));
        assertTrue(results.get("nonexistent").contains("error") || results.get("nonexistent").contains("not found"));
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
        assertEquals("sequential", strategy.getName());
    }
}
