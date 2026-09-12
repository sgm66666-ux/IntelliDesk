package com.intellidesk.document.chunk;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ChunkStrategyRegistry {

    private final Map<ChunkStrategyType, ChunkStrategy> strategies;

    public ChunkStrategyRegistry(List<ChunkStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(ChunkStrategy::type, Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "Duplicate chunk strategy for type: " + a.type());
                        }));
    }

    public ChunkStrategy getStrategy(ChunkStrategyType type) {
        ChunkStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown chunk strategy type: " + type);
        }
        return strategy;
    }
}
