package com.taskforge.worker;

import com.taskforge.domain.JobType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
class JobHandlerRegistry {

    private final Map<JobType, JobHandler> handlers;

    JobHandlerRegistry(List<JobHandler> registeredHandlers) {
        EnumMap<JobType, JobHandler> byType = new EnumMap<>(JobType.class);
        for (JobHandler handler : registeredHandlers) {
            JobType type = Objects.requireNonNull(handler.jobType(), "handler jobType");
            if (byType.putIfAbsent(type, handler) != null) {
                throw new IllegalStateException("Duplicate handler for " + type);
            }
        }
        handlers = Map.copyOf(byType);
    }

    JobHandler require(JobType type) {
        JobHandler handler = handlers.get(Objects.requireNonNull(type, "type"));
        if (handler == null) {
            throw new MissingJobHandlerException(type);
        }
        return handler;
    }
}
